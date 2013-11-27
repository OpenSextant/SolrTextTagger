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

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Tags maximum string of words in a corpus.  This is a callback-style API
 * in which you implement {@link #tagCallback(int, int, long)}.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public abstract class Tagger {

  private final Logger log = LoggerFactory.getLogger(Tagger.class);

  private final TaggerFstCorpus corpus;

  private final TokenStream tokenStream;
  //  private final CharTermAttribute termAtt;
  private final PositionIncrementAttribute posIncAtt;
  private final TermToBytesRefAttribute byteRefAtt;
  private final OffsetAttribute offsetAtt;
  private final TaggingAttribute lookupAtt;

  private final TagClusterReducer tagClusterReducer;

  private final boolean skipAltTokens;

  /**
   * Whether the WARNING about skipped tokens was already logged.
   */
  private boolean loggedSkippedAltTokenWarning = false;
  
  public Tagger(TaggerFstCorpus corpus, TokenStream tokenStream,
                TagClusterReducer tagClusterReducer, boolean skipAltTokens)
                        throws IOException {
    this.corpus = corpus;
    this.tokenStream = tokenStream;
    this.skipAltTokens = skipAltTokens;
//    termAtt = tokenStream.addAttribute(CharTermAttribute.class);
    byteRefAtt = tokenStream.addAttribute(TermToBytesRefAttribute.class);
    posIncAtt = tokenStream.addAttribute(PositionIncrementAttribute.class);
    offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
    lookupAtt = tokenStream.addAttribute(TaggingAttribute.class);
    tokenStream.reset();

    this.tagClusterReducer = tagClusterReducer;
  }

  public void process() throws IOException {

    //a shared pointer to the head used by this method and each Tag instance.
    final TagLL[] head = new TagLL[1];

    MyFstCursor<Long> cursor = null;
    //boolean switch used to log warnings in case tokens where skipped during
    //tagging.
    boolean skippedTokens = false; 
    while (tokenStream.incrementToken()) {
      if (log.isTraceEnabled()) {
        log.trace("Token: {}, posInc: {},  offset: [{},{}]",
            new Object[]{byteRefAtt, posIncAtt.getPositionIncrement(),
                offsetAtt.startOffset(), offsetAtt.endOffset()});
      }
      //check for posInc < 1 (alternate Tokens, such as expanded Synonyms)
      if (posIncAtt.getPositionIncrement() < 1) {
        //(a) Deal with this as a configuration issue and throw an exception
        if (!skipAltTokens) {
          throw new UnsupportedTokenException("Query Analyzer generates alternate "
              + "Tokens (posInc == 0). Please adapt your Analyzer configuration or "
              + "enable '" + TaggerRequestHandler.SKIP_ALT_TOKENS + "' to skip such "
              + "tokens. NOTE: enabling '" + TaggerRequestHandler.SKIP_ALT_TOKENS
              + "' might result in wrong tagging results if the index time analyzer "
              + "is not configured accordingly. For detailed information see "
              + "https://github.com/OpenSextant/SolrTextTagger/pull/11#issuecomment-24936225");
        } else {
          //(b) In case the index time analyser had indexed all variants (users
          //    need to ensure that) processing of alternate tokens can be skipped
          //    as anyways all alternatives will be contained in the FST.
          skippedTokens = true;
          log.trace("  ... ignored token");
          continue;
        }
      }
      //-- If PositionIncrement > 1 then finish all tags
//Deactivated as part of Solr 4.4 upgrade (see Issue-14 for details)
//      if (posInc > 1) {
//        log.trace("   - posInc > 1 ... mark cluster as done");
//        advanceTagsAndProcessClusterIfDone(head, -1);
//      }

      final int termId;
      //NOTE: we need to lookup tokens if
      // * the LookupAtt is true OR
      // * there are still advancing tags (to find the longest possible match)
      if (lookupAtt.isTaggable() || head[0] != null) {
        //-- Lookup the term id from the next token
        termId = getTermIdFromByteRef();
      } else { //no current cluster AND lookup == false ... 
        termId = -1; //skip this token
      }

      //-- Process tag
      advanceTagsAndProcessClusterIfDone(head, termId);

      //-- only create new Tags for Tokens we need to lookup
      if (lookupAtt.isTaggable() && termId >= 0) {

        //determine if the FST has the term as a start state
        // TODO use a cached bitset of starting termIds, which is faster than a failed FST
        // advance which is common
        if (cursor == null)
          cursor = new MyFstCursor<Long>(corpus.getPhrases());
        if (cursor.nextByLabel(termId)) {
          TagLL newTail = new TagLL(head, cursor, offsetAtt.startOffset(), offsetAtt.endOffset(),
              null);
          cursor = null;//because we can't share it with the next iteration
          //and add it to the end
          if (head[0] == null) {
            head[0] = newTail;
          } else {
            for (TagLL t = head[0]; true; t = t.nextTag) {
              if (t.nextTag == null) {
                t.addAfterLL(newTail);
                break;
              }
            }
          }
        }
      }//if termId >= 0
    }//end while(incrementToken())

    //-- Finish all tags
    advanceTagsAndProcessClusterIfDone(head, -1);
    assert head[0] == null;

    if(!loggedSkippedAltTokenWarning && skippedTokens){
      loggedSkippedAltTokenWarning = true; //only log once
      log.warn("The Tagger skiped some alternate tokens (tokens with posInc == 0) "
          + "while processing text. This may cause problems with some Analyer "
          + "configurations (e.g. query time synonym expansion). For details see "
          + "https://github.com/OpenSextant/SolrTextTagger/pull/11#issuecomment-24936225");
    }
    
    tokenStream.end();
    //tokenStream.close(); caller closes because caller acquired it
  }

  private void advanceTagsAndProcessClusterIfDone(TagLL[] head, int termId) throws IOException {
    //-- Advance tags
    final int endOffset = termId != -1 ? offsetAtt.endOffset() : -1;
    boolean anyAdvance = false;
    for (TagLL t = head[0]; t != null; t = t.nextTag) {
      anyAdvance |= t.advance(termId, endOffset);
    }

    //-- Process cluster if done
    if (!anyAdvance && head[0] != null) {
      tagClusterReducer.reduce(head);
      for (TagLL t = head[0]; t != null; t = t.nextTag) {
        tagCallback(t.startOffset, t.endOffset, t.value);
      }
      head[0] = null;
    }
  }

  private int getTermIdFromByteRef() {
    byteRefAtt.fillBytesRef();
    BytesRef bytesRef = byteRefAtt.getBytesRef();
    int length = bytesRef.length;
    if (length == 0) {
      throw new IllegalArgumentException("term: " + bytesRef.utf8ToString() + " analyzed to a " +
          "zero-length token");
    }
    return corpus.lookupTermId(bytesRef);//-1 if not found
  }

  /**
   * Invoked by {@link #process()} for each tag found.  endOffset is always >= the endOffset
   * given in the previous
   * call.
   *
   * @param startOffset The character offset of the original stream where the tag starts.
   * @param endOffset   One more than the character offset of the original stream where the tag
   *                    ends.
   * @param docIdsKey   A reference to the matching docIds that can be resolved via {@link
   * #lookupDocIds(long)}.
   */
  protected abstract void tagCallback(int startOffset, int endOffset, long docIdsKey);

  /**
   * Returns a sorted array of integer docIds given the corresponding key.
   *
   * @param docIdsKey The lookup key.
   * @return Not null
   */
  protected IntsRef lookupDocIds(long docIdsKey) {
    return corpus.getDocIdsByPhraseId(docIdsKey);
  }
}
