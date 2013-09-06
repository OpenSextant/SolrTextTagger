/*
 This software was produced for the U. S. Government
 under Contract No. W15P7T-11-C-F600, and is
 subject to the Rights in Noncommercial Computer Software
 and Noncommercial Computer Software Documentation
 Clause 252.227-7014 (JUN 1995)

 Copyright 2013 The MITRE Corporation. All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.opensextant.solrtexttagger;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.store.InputStreamDataInput;
import org.apache.lucene.store.OutputStreamDataOutput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.fst.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Limitations:
 * No more than Integer.MAX_VALUE unique words
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public class TaggerFstCorpus implements Serializable {

  private static final Logger log = LoggerFactory.getLogger(TaggerFstCorpus.class);

  private static final PositiveIntOutputs fstOutputs = PositiveIntOutputs.getSingleton(true);
  private static final int MAX_PHRASE_LEN = 10;

  // characters in value:
  private int minLen = 0;
  private int maxLen = 0;

  //a word to unique id mapping.  The id is actually limited to Integer, not Long
  private transient FST<Long> dict;//Input = a word   (COULD BE NULL!)
  private transient FST<Long> phrases;//Input = a sequence of integers (lookup in dict)
  private int[] docIdsHeap;
  private int[] docIdsLookup;

  private int totalDocIdRefs = -1;//== idsHeap.length
  private boolean partialMatches;
  private String indexedFieldName;
  private String storedFieldName;
  private long indexVersion;

  // *****************************************
  //        Build
  // *****************************************

  /**
   *
   * @param reader The IndexReader; doesn't matter which subclass it may be.
   * @param indexVersion
   *
   * @param reader The IndexReader; doesn't matter which subclass it may be.
   * @param docBits Optional; indicates which documents to use from the reader.
   * @param indexedFieldName An indexed field to load the term dict from.
   * @param storedFieldName A stored field with text to be analyzed by the
   *                        Analyzer from indexedFieldName
   * @param analyzer The analyzer of indexedFieldName
   * @param partialMatches Whether to add dictionary entries for all substring
   *                       combinations of the name phrases or just complete
   *                       phrases
   * @throws IOException
   */
  public TaggerFstCorpus(IndexReader reader, long indexVersion, Bits docBits,
                         String indexedFieldName, String storedFieldName,
                         Analyzer analyzer,
                         boolean partialMatches, int minLen, int maxLen)
      throws IOException {
    log.info("Building TaggerFstCorpus");
    this.indexVersion = indexVersion;
    this.indexedFieldName = indexedFieldName;
    this.storedFieldName = storedFieldName;
    this.partialMatches = partialMatches;
    this.maxLen = maxLen;
    this.minLen = minLen;

    //So this is slower but for this use-case it's totally fine
    AtomicReader atomicReader = SlowCompositeReaderWrapper.wrap(reader);

    buildDict(atomicReader, indexedFieldName);
    if (dict != null) {

      if (docBits == null)
        docBits = atomicReader.getLiveDocs();

      HashMap<IntsRef, IntsRef> workingSet = buildTempWorkingPhrases(atomicReader,
          docBits, analyzer, storedFieldName);
      assert totalDocIdRefs >= 0;

      IntsRef[] termIdsPhrases = buildSortedPhrasesAndIdTables(workingSet);//modifies workingSet too, FYI
      assert docIdsHeap != null && docIdsLookup != null;
      workingSet = null;//GC

      buildPhrasesFST(termIdsPhrases);
    }

    if (log.isTraceEnabled()) {
      printDebugInfo();
    }
  }

  private void buildDict(AtomicReader reader, String indexedFieldName) throws IOException {
    log.debug("Building word dict FST...");
    //shareOutputs=true so we can do reverse lookup (lookup by ord id)
    Builder<Long> builder = new Builder<Long>(FST.INPUT_TYPE.BYTE1, fstOutputs);

    Terms terms = reader.terms(indexedFieldName);//already sorted, of course
    if (terms != null) {
      TermsEnum termsEnum = terms.iterator(null);
      long dictId = 0;
      IntsRef scratchInts = new IntsRef();
      for (BytesRef termRef = termsEnum.next(); termRef != null; termRef = termsEnum.next()) {
        builder.add(Util.toIntsRef(termRef, scratchInts), dictId++);
      }
    }
    dict = builder.finish();//NOTE: returns null!  Bad API design -- LUCENE-4285
  }

  /**
   * Builds workingSet: a Map of phrases to internal docIds. The phrases are in
   * in the form of an array of integers to lookup in dict for the word.
   */
  private HashMap<IntsRef, IntsRef>
      buildTempWorkingPhrases(IndexReader reader, Bits docBits, Analyzer analyzer,
                              String storedFieldName) throws IOException {
    log.debug("Building temporary phrase working set...");
    //Maps dictIds (i.e. ordered list of ids of words comprising a phrase) to a
    //   set of Solr document ids (uniqueKey in schema)
    // Lucene 4.2.1 API Warning  dict.getArcWithOutputCount() returns long now; not int. 
    HashMap<IntsRef, IntsRef> workingSet = new HashMap<IntsRef, IntsRef>((int)dict.getArcWithOutputCount() * 2);
    Set<String> fieldNames = new HashSet<String>();
    fieldNames.add(storedFieldName);

    totalDocIdRefs = 0;

    PhraseBuilder paths = new PhraseBuilder(4); //one instance reused for all analyzed labels
    //get indexed terms for each live docs
    for (int docId = 0; docId < reader.maxDoc(); docId++) {
      if (docBits != null && !docBits.get(docId))
        continue;
      final Document document = reader.document(docId, fieldNames);
      //use document.getFields(..) to support multivalued fields!
      IndexableField[] storedFields = document.getFields(storedFieldName);
      if (storedFields.length == 0) {
        //Issue #5: in multilingual setting there will be entities that do not have a
        //          labels for all languages. In this case the storedField will be
        //          null. However
        //TODO: To check if the parsed field is Stored one should check the Schema
        //      of the Index
        // throw new RuntimeException("docId "+docId+" field '"+ storedFieldName+"': missing stored value");
        //use a trace level logging instead
        log.trace("docId {} has no (stored) value for field '{}':", docId,storedFieldName);
        continue;
      }
      for (IndexableField storedField : storedFields) {
        String phraseStr = storedField.stringValue();

        if (phraseStr.length() < minLen || phraseStr.length() > maxLen) {
          log.warn("Text: {} was completely eliminated by analyzer for tagging; Too long or too short. LEN={}", phraseStr, phraseStr.length());
          continue;          
        }
  
        //analyze stored value to array of terms (their Ids)
        boolean added = false;
        for (IntsRef phraseIdRef : analyze(analyzer, phraseStr, paths)) {
          if (phraseIdRef.length == 0) {
            continue;
          }
          if (partialMatches) {
            //shingle the phrase (aka n-gram but word level, not character)
            IntsRef shingleIdRef = null;//lazy init, and re-used too
            assert phraseIdRef.offset == 0;
            for (int offset = 0; offset < phraseIdRef.length; offset++) {
              for (int length = 1; offset + length <= phraseIdRef.length && length <= MAX_PHRASE_LEN; length++) {
                if (shingleIdRef == null) {
                  shingleIdRef = new IntsRef(phraseIdRef.ints, offset, length);
                } else {
                  shingleIdRef.offset = offset;
                  shingleIdRef.length = length;
                }
                if (addIdToWorkingSetValue(workingSet, shingleIdRef, docId)) {
                  shingleIdRef = null;
                }
                added = true;
                totalDocIdRefs++;//since we added the docId
              }
            }
          } else {
            //add complete phrase
            addIdToWorkingSetValue(workingSet, phraseIdRef, docId);
            added = true;
            totalDocIdRefs++;//since we added the docId
          }
        }
        if (!added) { //warn if we have not added anything for a label
          log.warn("Text: {} was completely eliminated by analyzer for tagging", phraseStr);
        }
        //TODO consider counting by stored-value (!= totalDocIdRefs when partialMatches==true)
        if (totalDocIdRefs % 100000 == 0) {
          log.info("Total records reviewed COUNT={}",totalDocIdRefs);
        }
      }//for each stored value
    }//for each doc
    log.info("Reviewed all COUNT={} documents",totalDocIdRefs);
    //TODO: this write a warning if not a single stored field was found for the
    //      parsed storedFieldName - as this will most likely indicate a wrong
    //      schema configuration.
    //      This should be replace by an explicit check against the schema.
    if (totalDocIdRefs == 0 && reader.maxDoc() > 0) {
      log.warn("No stored valued for field '{}' in {} processed Documents. Please check "
          + "Solr Schema configuration and ensure that this field is stored!",
          storedFieldName, reader.maxDoc());
    }

    log.debug("Phrase working set has "+workingSet.size()+" entries, "+ totalDocIdRefs +" id references.");
    return workingSet;
  }

  /** returns whether added a new entry and thus can no longer use phraseIdRef */
  private boolean addIdToWorkingSetValue(HashMap<IntsRef, IntsRef> workingSet, IntsRef phraseIdRef, int docId) {
    IntsRef docIdsRef = workingSet.get(phraseIdRef);
    if (docIdsRef == null) {
      docIdsRef = new IntsRef(new int[]{docId}, 0, 1);
      workingSet.put(phraseIdRef, docIdsRef);
      return true;
    } else {
      assert docIdsRef.offset == 0;
      //note: could check if it doesn't already have this id, but unlikely and has no bad consequence
      docIdsRef.grow(docIdsRef.length + 1);//ensures the array has capacity
      docIdsRef.ints[docIdsRef.length++] = docId;
      return false;
    }
  }

  /**
   * Analyzes the text argument, converting each term into the corresponding id
   * and concatenating into the result, a list of ids.
   * @param analyzer the Lucene {@link Analyzer} used to process the text
   * @param text the text to analyze
   * @param paths the {@link PhraseBuilder} instance used to serialize the 
   * {@link TokenStream}. If not <code>null</code> the instance will be
   * {@link PhraseBuilder#reset() reset} otherwise a new Paths instance will be
   * created.
   * @return the phrases extracted from the TokenStream. Each phrase is
   * represented by an {@link IntsRef} where single words are represented by the
   * <code>int termId</code>.
   */
  private IntsRef[] analyze(Analyzer analyzer, String text, PhraseBuilder paths) throws IOException {
    if(paths == null){
        paths = new PhraseBuilder(4);
    } else {
        paths.reset(); //reset the paths instance before usage
    }
    TokenStream ts = analyzer.tokenStream("", new StringReader(text));
    TermToBytesRefAttribute byteRefAtt = ts.addAttribute(TermToBytesRefAttribute.class);
    PositionIncrementAttribute posIncAtt = ts.addAttribute(PositionIncrementAttribute.class);
    PositionLengthAttribute posLenAtt = ts.addAttribute(PositionLengthAttribute.class);
    OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
    //for trace level debugging the consumed Tokens and the generated Paths
    CharTermAttribute termAtt = null; //trace level debugging only
    Map<Integer,String> termIdMap = null; //trace level debugging only
    if(log.isTraceEnabled()){
      termAtt = ts.addAttribute(CharTermAttribute.class);
      termIdMap = new HashMap<Integer,String>();
    }
    ts.reset();
    //result.length = 0;
    while (ts.incrementToken()) {
      int posInc = posIncAtt.getPositionIncrement();
      if(posInc > 1){
        //TODO: maybe we do not need this
        throw new IllegalArgumentException("term: " + text + " analyzed to a "
            + "token with posinc " + posInc + " (posinc MUST BE 0 or 1)");
      }
      byteRefAtt.fillBytesRef();
      BytesRef termBr = byteRefAtt.getBytesRef();
      int length = termBr.length;
      if (length == 0) { //ignore term (NOTE: that 'empty' is not set)
        OffsetAttribute offset = ts.addAttribute(OffsetAttribute.class);
        log.warn("token [{}, {}] or term: {} analyzed to a zero-length token",
            new Object[]{offset.startOffset(),offset.endOffset(),text});
      } else { //process term
        int termId = lookupTermId(termBr);
        if(log.isTraceEnabled()){
          log.trace("Token: {}, posInc: {}, posLen: {}, offset: [{},{}], termId {}",
            new Object[]{termAtt, posInc, posLenAtt.getPositionLength(),
                offsetAtt.startOffset(), offsetAtt.endOffset(), termId});
        }
       if (termId == -1) {
          //westei: changed this to a warning as I was getting this for terms with some
          //rare special characters e.g. '∀' (for all) and a letter looking
          //similar to the greek letter tau.
          //in any way it looked better to ignore such terms rather than failing
          //with an exception and having no FST at all.
          log.warn("Couldn't lookup term TEXT=" + text + " TERM="+termBr.utf8ToString());
          //throw new IllegalStateException("Couldn't lookup term TEXT=" + text + " TERM="+termBr.utf8ToString());
        } else {
          if(log.isTraceEnabled()){
            termIdMap.put(termId,termAtt.toString());
          }
          try {
            paths.addTerm(termId, offsetAtt.startOffset(), offsetAtt.endOffset(),
                posInc, posLenAtt.getPositionLength());
          } catch (UnsupportedTokenException e) {
            //catch because here we can also print the text that failed to encode
            log.error("Problematic Token '{}'[offset:[{},{}], posInc: {}] of Text '{}' ",
                new Object[]{ byteRefAtt, offsetAtt.startOffset(), 
                    offsetAtt.endOffset(), posInc, text});
            throw e;
          }
        }
      }
    }
    ts.end();
    ts.close();
    IntsRef[] intsRefs = paths.getPhrases();
    if(log.isTraceEnabled()){
      int n = 1;
      for(IntsRef ref : intsRefs){
          StringBuilder sb = new StringBuilder();
          for(int i = ref.offset; i<ref.length;i++){
              sb.append(termIdMap.get(ref.ints[i])).append(" ");
          }
          log.trace(" {}: {}",n++,sb);
      }
    }
    return intsRefs;
  }
  
  /** Takes workingSet and returns sorted phrases and build ext doc id tables. */
  private IntsRef[] buildSortedPhrasesAndIdTables(HashMap<IntsRef, IntsRef> workingSet) throws IOException {
    log.debug("Building doc ID lookup tables...");
    //basically we convert workingSet to an array of sorted keys, an output doc ids lookup
    // table (lookup into idsHeap), and a large array of ints to efficiently
    // store all doc id references, of where there are potentially more than one
    // per input key
    docIdsHeap = new int[totalDocIdRefs];
    docIdsLookup = new int[workingSet.size()+1];
    //add an extra element at the end to point beyond 1 spot beyond the heap;
    // this makes the lookup code simpler
    docIdsLookup[docIdsLookup.length-1] = docIdsHeap.length;
    IntsRef[] termIdsPhrases = workingSet.keySet().toArray(new IntsRef[workingSet.size()]);
    Arrays.sort(termIdsPhrases);
    int nextHeapOffset = 0;
    int maxPhraseLen = 0;
    int maxDocsLen = 0;
    for (int i = 0; i < termIdsPhrases.length; i++) {
      IntsRef termIdsPhrase = termIdsPhrases[i];
      docIdsLookup[i] = nextHeapOffset;
      IntsRef docIds = workingSet.remove(termIdsPhrase);//remove to GC

      if (termIdsPhrase.length > maxPhraseLen) {
        maxPhraseLen = termIdsPhrase.length;
        if (maxPhraseLen > 4  && log.isDebugEnabled())
          log.debug("Max phrase len: "+maxPhraseLen+": "+outputPhrase(dict,termIdsPhrase));
      }
      if (docIds.length > maxDocsLen) {
        maxDocsLen = docIds.length;
        if (maxDocsLen > 500 && log.isDebugEnabled())
          log.debug("Max Docs/phrase: "+maxDocsLen+" for phrase: "+outputPhrase(dict,termIdsPhrase)+
              " (1st doc id: "+docIds.ints[0]+")");
      }

      Arrays.sort(docIds.ints, docIds.offset, docIds.offset + docIds.length);
      System.arraycopy(docIds.ints, docIds.offset, docIdsHeap, nextHeapOffset, docIds.length);
      nextHeapOffset += docIds.length;
    }
    assert nextHeapOffset == docIdsHeap.length;
    return termIdsPhrases;
  }

  /** Takes sorted phrases, and builds an FST with ord output (saves to {@link #phrases}. */
  private void buildPhrasesFST(IntsRef[] termIdsPhrases) throws IOException {
    log.debug("Building phrases FST...");
    //build the FST from the workingSet
    Builder<Long> builder = new Builder<Long>(FST.INPUT_TYPE.BYTE4, fstOutputs);

    for (int i = 0; i < termIdsPhrases.length; i++) {
      IntsRef termIdsPhrase = termIdsPhrases[i];
      assert termIdsPhrase.length > 0;
      builder.add(termIdsPhrase, (long)i);
    }
    phrases = builder.finish();
  }

  // *****************************************
  //        Core Public Interface
  // *****************************************

  /**
   * The surrogate id for the word.
   * @param word
   * @return -1 if not found else >= 0
   */
  public int lookupTermId(BytesRef word) {
    if (dict == null)
      return -1;
    try {
      Long val = Util.get(dict,word);
      if (val == null)
        return -1;
      return val.intValue();
    } catch (IOException e) {
      throw new RuntimeException(e);//weird
    }
  }

  public FST<Long> getPhrases() {
    return phrases;
  }

  public IntsRef getDocIdsByPhraseId(long phraseId) {
    int offset = docIdsLookup[(int)phraseId];
    int nextOffset = docIdsLookup[(int)phraseId + 1];
    //FYI docIdsLookup is 1 greater than actual phrase ids so we don't have
    // to do an array bounds check here
    return new IntsRef(docIdsHeap,offset,nextOffset - offset);
  }

  public boolean getPartialMatches() {
    return partialMatches;
  }

  // ************** DIAGNOSTIC *************

  public void printDebugInfo() throws IOException {
    if (dict == null) {
      log.info("Empty FSTs");
      return;
    }
    writeFstAsDot(dict, "dict");
    if (dict.getArcWithOutputCount() <= 100) {
      PrintWriter out = new PrintWriter(new File("dict.txt"));
      try {
        out.println("term | termId");
        BytesRefFSTEnum<Long> iter = new BytesRefFSTEnum<Long>(dict);
        BytesRefFSTEnum.InputOutput<Long> entry;
        while((entry = iter.next()) != null) {
          out.print(entry.input.utf8ToString());
          out.print('|');
          out.println(entry.output);
        }
      } finally {
        out.close();
      }
    }
    writeFstAsDot(phrases, "phrases");
    if (phrases.getArcWithOutputCount() <= 20) {
      PrintWriter out = new PrintWriter(new File("phrases.txt"));
      try {
        out.println("phrase | docIds");
        IntsRefFSTEnum<Long> iter = new IntsRefFSTEnum<Long>(phrases);
        IntsRefFSTEnum.InputOutput<Long> entry;
        while((entry = iter.next()) != null) {
          IntsRef termIds = entry.input;
          String buf = termIdPhraseToString(termIds);
          out.print(buf);
          out.print('|');
          IntsRef docIds = getDocIdsByPhraseId(entry.output);
          for(int i = 0; i < docIds.length; i++) {
            out.print(' ');
            out.print(docIds.ints[docIds.offset+i]);
          }
          out.println();
        }
      } finally {
        out.close();
      }
    }
    log.info("Built FST " + dict.getArcWithOutputCount() + " terms (" + dict.sizeInBytes() / 1024 + "kb).");
    log.info("Built FST " + phrases.getArcWithOutputCount() + " phrases (" + phrases.sizeInBytes() / 1024 + "kb).");
    int idsBytes = (docIdsHeap.length + docIdsLookup.length) * 4;
    log.info("Hold heap of " + docIdsHeap.length + " docIds consuming " + (idsBytes / 1024) + "kb).");
  }

  private String termIdPhraseToString(IntsRef termIds) throws IOException {
    StringBuilder buf = new StringBuilder(termIds.length*10);
    for(int i = 0; i < termIds.length; i++) {
      int termId = termIds.ints[termIds.offset + i];
      String word = lookupWord(termId);
      if (buf.length() != 0)
        buf.append(' ');
      buf.append(word);
    }
    return buf.toString();
  }

  private String lookupWord(int termId) throws IOException {
    IntsRef wordIntsRef = Util.getByOutput(dict, termId);
    return Util.toBytesRef(wordIntsRef, new BytesRef(wordIntsRef.length)).utf8ToString();
  }

  @SuppressWarnings("unchecked")
  private void writeFstAsDot(FST fst, String fname) {
    if (fst == null || fst.getNodeCount() > 200)
      return;
    try {
      PrintWriter out = new PrintWriter(fname + ".gv");
      Util.toDot(fst, out, true, true);
      out.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String outputPhrase(FST<Long> dict, IntsRef termIdsPhrase) throws IOException {
    StringBuilder buf = new StringBuilder(100);
    BytesRef scratch = new BytesRef();
    for (int i = 0; i < termIdsPhrase.length; i++) {
      int wordId = termIdsPhrase.ints[termIdsPhrase.offset + i];
      IntsRef wordInts = Util.getByOutput(dict, wordId);
      String word = Util.toBytesRef(wordInts,scratch).utf8ToString();
      buf.append(word);
      buf.append(' ');
    }
    return buf.toString();
  }

  // ************** SAVE & LOAD *************

  public void save(File file) throws IOException {
    log.info("Saving " + file);
    ObjectOutputStream outputStream = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
    try {
      outputStream.writeObject(this);
    } finally {
      outputStream.close();
    }
  }

  public static TaggerFstCorpus load(File file) throws IOException {
    log.info("Loading "+file);
    ObjectInputStream inputStream = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)));
    try {
      return (TaggerFstCorpus) inputStream.readObject();
    } catch (ClassNotFoundException e) {
      throw new IOException(e.toString(),e);
    } finally {
      inputStream.close();
    }
  }

  /** required for Serializable */
  private void writeObject(ObjectOutputStream outputStream) throws IOException {
    outputStream.defaultWriteObject();
    OutputStreamDataOutput outputStreamDataOutput = new OutputStreamDataOutput(outputStream);
    if (dict != null) {
      dict.save(outputStreamDataOutput);
      phrases.save(outputStreamDataOutput);
    }
  }

  /** required for Serializable */
  private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
    inputStream.defaultReadObject();
    InputStreamDataInput inputStreamDataInput = new InputStreamDataInput(inputStream);
    //TODO what if there is no dict/fst (they are empty?)
    dict = new FST<Long>(inputStreamDataInput, fstOutputs);
    phrases = new FST<Long>(inputStreamDataInput, fstOutputs);
  }

  public long getIndexVersion() {
    return indexVersion;
  }

  public boolean wasInitializedWith(long indexVersion, String indexedField, String storedField, boolean partialMatches, int minLen, int maxLen) {
    return this.indexVersion == indexVersion &&
        this.indexedFieldName.equals(indexedField) &&
        this.storedFieldName.equals(storedField) &&
        this.partialMatches == partialMatches &&
        this.minLen == minLen &&
        this.maxLen == maxLen;
  }

  public String getIndexedField() {
    return indexedFieldName;
  }

}
