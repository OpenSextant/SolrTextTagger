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

import com.google.common.io.CharStreams;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.OpenBitSet;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

/**
 * Scans posted text, looking for matching strings in the Solr index.
 * The public static final String members are request parameters.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public class TaggerRequestHandler extends RequestHandlerBase {

  /** Request parameter. */
  private static final String OVERLAPS = "overlaps";
  /** Request parameter. */
  public static final String TAGS_LIMIT = "tagsLimit";
  /** Request parameter. */
  @Deprecated
  public static final String SUB_TAGS = "subTags";
  /** Request parameter. */
  public static final String MATCH_TEXT = "matchText";
  /** Request parameter. */
  public static final String SKIP_ALT_TOKENS = "skipAltTokens";

  private final Logger log = LoggerFactory.getLogger(getClass());

  private TaggerFstCorpus _corpus;//synchronized access
  
  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    setTopInitArgsAsInvariants(req);

    boolean build = req.getParams().getBool("build", false);
    TaggerFstCorpus corpus = getCorpus(build, req, rsp);
    if (build)//just build; that's it.
      return;

    final TagClusterReducer tagClusterReducer;
    String overlaps = req.getParams().get(OVERLAPS);
    if (overlaps == null) {//deprecated; should always be specified
      if (req.getParams().getBool(SUB_TAGS, false))//deprecated
        tagClusterReducer = TagClusterReducer.ALL;
      else
        tagClusterReducer = TagClusterReducer.NO_SUB;
    } else if (overlaps.equals("ALL")) {
      tagClusterReducer = TagClusterReducer.ALL;
    } else if (overlaps.equals("NO_SUB")) {
      tagClusterReducer = TagClusterReducer.NO_SUB;
    } else if (overlaps.equals("LONGEST_DOMINANT_RIGHT")) {
      tagClusterReducer = TagClusterReducer.LONGEST_DOMINANT_RIGHT;
    } else {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "unknown tag overlap mode: "+overlaps);
    }

    final int rows = req.getParams().getInt(CommonParams.ROWS, 10000);
    final int tagsLimit = req.getParams().getInt(TAGS_LIMIT, 1000);
    final boolean addMatchText = req.getParams().getBool(MATCH_TEXT, false);
    final String indexedField = corpus.getIndexedField();
    final SchemaField idSchemaField = req.getSchema().getUniqueKeyField();
    final boolean skipAltTokens = req.getParams().getBool(SKIP_ALT_TOKENS, false);
    
    //Get posted data
    Reader reader = null;
    Iterable<ContentStream> streams = req.getContentStreams();
    if (streams != null) {
      Iterator<ContentStream> iter = streams.iterator();
      if (iter.hasNext()) {
        reader = iter.next().getReader();
      }
      if (iter.hasNext()) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            getClass().getSimpleName()+" does not support multiple ContentStreams");
      }
    }
    if (reader == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          getClass().getSimpleName()+" requires text to be POSTed to it");
    }
    final String bufferedInput;
    if (addMatchText) {
      //read the input fully into a String buffer we'll use later to get
      // the match text, then replace the input with a reader wrapping the buffer.
      bufferedInput = CharStreams.toString(reader);//(closes reader)
      reader = new StringReader(bufferedInput);
    } else {
      bufferedInput = null;//not used
    }

    final SolrIndexSearcher searcher = req.getSearcher();
    final OpenBitSet matchDocIdsBS = new OpenBitSet(searcher.maxDoc());
    final List tags = new ArrayList(2000);

    try {
      //use the QueryAnalyzer for tagging parsed Text. Especially when using
      //WordDelimiterFilter one might want to use different analyzer configs
      //for indexing (building the FST) and tagging.
      Analyzer analyzer = req.getSchema().getField(indexedField).getType().getQueryAnalyzer();
      TokenStream tokenStream = analyzer.tokenStream("", reader);
      try {
        new Tagger(corpus, tokenStream, tagClusterReducer, skipAltTokens) {
          @SuppressWarnings("unchecked")
          @Override
          protected void tagCallback(int startOffset, int endOffset, long docIdsKey) {
            if (tags.size() >= tagsLimit)
              return;
            NamedList tag = new NamedList();
            tag.add("startOffset", startOffset);
            tag.add("endOffset", endOffset);
            if (addMatchText)
              tag.add("matchText", bufferedInput.substring(startOffset,
                  endOffset));
            //below caches, and also flags matchDocIdsBS
            tag.add("ids", lookupSchemaDocIds(docIdsKey));
            tags.add(tag);
          }

          Map<Long,List> docIdsListCache = new HashMap<Long, List>(2000);

          ValueSourceAccessor uniqueKeyCache = new ValueSourceAccessor(searcher,
              idSchemaField.getType().getValueSource(idSchemaField, null));

          @SuppressWarnings("unchecked")
          private List lookupSchemaDocIds(long docIdsKey) {
            List schemaDocIds = docIdsListCache.get(docIdsKey);
            if (schemaDocIds != null)
              return schemaDocIds;
            IntsRef docIds = lookupDocIds(docIdsKey);
            //translate lucene docIds to schema ids
            schemaDocIds = new ArrayList(docIds.length);
            for (int i = docIds.offset; i < docIds.offset + docIds.length; i++) {
              int docId = docIds.ints[i];
              matchDocIdsBS.set(docId);//also, flip docid in bitset
              schemaDocIds.add(uniqueKeyCache.objectVal(docId));//translates here
            }
            docIdsListCache.put(docIdsKey, schemaDocIds);
            return schemaDocIds;
          }

        }.process();
      } finally {
        tokenStream.close();
      }
    } finally {
      reader.close();
    }
    rsp.add("tagsCount",tags.size());
    rsp.add("tags", tags);

    rsp.setReturnFields(new SolrReturnFields( req ));

    //Now we must supply a Solr DocList and add it to the response.
    //  Typically this is gotten via a SolrIndexSearcher.search(), but in this case we
    //  know exactly what documents to return, the order doesn't matter nor does
    //  scoring.
    //  Ideally an implementation of DocList could be directly implemented off
    //  of a BitSet, but there are way too many methods to implement for a minor
    //  payoff.
    int matchDocs = (int) matchDocIdsBS.cardinality();
    int[] docIds = new int[ Math.min(rows, matchDocs) ];
    DocIdSetIterator docIdIter = matchDocIdsBS.iterator();
    for (int i = 0; i < docIds.length; i++) {
      docIds[i] = docIdIter.nextDoc();
    }
    DocList docs = new DocSlice(0, docIds.length, docIds, null, matchDocs, 1f);
    rsp.add("matchingDocs", docs);
  }

  /**
   * This request handler supports configuration options defined at the top level as well as
   * those in typical Solr 'defaults', 'appends', and 'invariants'.  The top level ones are treated
   * as invariants.
   */
  private void setTopInitArgsAsInvariants(SolrQueryRequest req) {
    // First convert top level initArgs to SolrParams
    HashMap<String,String> map = new HashMap<String,String>();
    for (int i=0; i<initArgs.size(); i++) {
      Object val = initArgs.getVal(i);
      if (val != null && !(val instanceof NamedList))
        map.put(initArgs.getName(i), val.toString());
    }
    SolrParams topInvariants = new MapSolrParams(map);
    // By putting putting the top level into the 1st arg, it overrides request params in 2nd arg.
    req.setParams(SolrParams.wrapDefaults(topInvariants, req.getParams()));
  }

  /** Gets the corpus if it's ready and not stale, otherwise initializes it. */
  private synchronized TaggerFstCorpus getCorpus(boolean forceBuild,
      SolrQueryRequest req, SolrQueryResponse rsp) throws IOException {
    long indexVersion = req.getSearcher().getIndexReader().getVersion();
    if (_corpus != null && indexVersion != _corpus.getIndexVersion())
      forceBuild = true;
    if (forceBuild || _corpus == null) {
      rsp.setHttpCaching(false);
      _corpus = null;//help GC
      _corpus = initCorpus(req, forceBuild);
    }
    return _corpus;
  }

  private TaggerFstCorpus initCorpus(SolrQueryRequest req, boolean forceRebuild) throws IOException {
    SolrParams params = req.getParams();
    //--load params
    String indexedField = params.get("indexedField");
    if (indexedField == null)
      throw new RuntimeException("required param 'indexedField'");
    String storedField = params.get("storedField", indexedField);
    boolean partialMatches = params.getBool("partialMatches", false);
    String taggerCacheFile = params.get("cacheFile");
    File cacheFile = (taggerCacheFile == null ? null : new File(req.getCore().getDataDir(), taggerCacheFile));
    String corpusFilterQuery = params.get("fq");
    int minLen = params.getInt("valueMinLen", 1);
    int maxLen = params.getInt("valueMaxLen", 100);

    //--Potentially check if can read a cached file from disk
    SolrIndexSearcher searcher = req.getSearcher();
    long indexVersion = searcher.getIndexReader().getVersion();

    if (!forceRebuild) {
      if (cacheFile != null && cacheFile.exists()) {
        try {
          TaggerFstCorpus corpus = TaggerFstCorpus.load(cacheFile);
          //ensure it was initialized the same
          if (corpus.wasInitializedWith(indexVersion, indexedField, storedField, partialMatches, minLen, maxLen))
            return corpus;
        } catch (Exception e) {
          SolrException.log(log, e);
          log.warn("Couldn't load saved tagger file so re-creating.");
        }
      }
    }

    //--Find the set of documents matching the provided 'fq' (filter query)
    Bits docBits = null;
    if (corpusFilterQuery != null) {
      SolrQueryRequest solrReq = new LocalSolrQueryRequest(req.getCore(), params);
      Query filterQuery = null;
      try {
        QParser qParser = QParser.getParser(corpusFilterQuery, null, solrReq);
        filterQuery = qParser.parse();
      // } catch (ParseException e) { /* Solr4.0 */
      } catch (SyntaxError e) { /* Solr4.1 */
        throw new RuntimeException(e);
      }
      final DocSet docSet = searcher.getDocSet(filterQuery);
      //note: before Solr 4.7 we could call docSet.getBits() but no longer.
      if (docSet instanceof BitDocSet) {
        docBits = ((BitDocSet)docSet).getBits();
      } else {
        docBits = new Bits() {

          @Override
          public boolean get(int index) {
            return docSet.exists(index);
          }

          @Override
          public int length() {
            return docSet.size();
          }
        };
      }
    }

    //--Do the building
    Analyzer analyzer = searcher.getSchema().getField(indexedField).getType().getAnalyzer();
    TaggerFstCorpus corpus;
    //synchronized semi-globally because this is an intensive operation we don't
    // want more than one tagger on the system doing at one time.
    synchronized (getClass()) {
      corpus = new TaggerFstCorpus(
          searcher.getIndexReader(), indexVersion, docBits,
          indexedField, storedField, analyzer, partialMatches, minLen, maxLen);
    }

    if (cacheFile != null) {
      try {
        corpus.save(cacheFile);
      } catch (IOException e) {
        log.error("Couldn't save tagger cache to " + cacheFile + " because " + e.toString(), e);
      }
    }
    return corpus;
  }

  @Override
  public String getDescription() {
    return "Processes input text to find stand-off named tags against a large corpus.";
  }

  @Override
  public String getSource() {
    return "$HeadURL$";
  }

}

/** See LUCENE-4541 or {@link org.apache.solr.response.transform.ValueSourceAugmenter}. */
class ValueSourceAccessor {
  // implement FunctionValues ?
  private final List<AtomicReaderContext> readerContexts;
  private final FunctionValues[] docValuesArr;
  private final ValueSource valueSource;
  private final Map fContext;

  private int localId;
  private FunctionValues values;

  public ValueSourceAccessor(IndexSearcher searcher, ValueSource valueSource) {
    readerContexts = searcher.getIndexReader().leaves();
    this.valueSource = valueSource;
    docValuesArr = new FunctionValues[readerContexts.size()];
    fContext = ValueSource.newContext(searcher);
  }

  private void setState(int docid) {
    int idx = ReaderUtil.subIndex(docid, readerContexts);
    AtomicReaderContext rcontext = readerContexts.get(idx);
    values = docValuesArr[idx];
    if (values == null) {
      try {
        docValuesArr[idx] = values = valueSource.getValues(fContext, rcontext);
      } catch (IOException e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      }
    }
    localId = docid - rcontext.docBase;
  }

  public Object objectVal(int docid) {
    setState(docid);
    return values.objectVal(localId);
  }

  //...
}
