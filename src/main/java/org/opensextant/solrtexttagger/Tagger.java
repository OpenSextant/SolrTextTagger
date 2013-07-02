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
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRef;

import java.io.IOException;
import java.io.Reader;

/**
 * Tags maximum string of words in a corpus.  This is a callback-style API
 * in which you implement {@link #tagCallback(int, int, long)}.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public abstract class Tagger {

  private final TaggerFstCorpus corpus;

  private final TokenStream tokenStream;
  //private final CharTermAttribute termAtt;
  private final PositionIncrementAttribute posIncAtt;
  private final TermToBytesRefAttribute byteRefAtt;
  private final OffsetAttribute offsetAtt;

  private final TagClusterReducer tagClusterReducer;

  public Tagger(TaggerFstCorpus corpus, Analyzer analyzer, Reader reader,
                TagClusterReducer tagClusterReducer) throws IOException {
    this.corpus = corpus;
    tokenStream = analyzer.tokenStream("", reader);
    //termAtt = tokenStream.addAttribute(CharTermAttribute.class);
    byteRefAtt = tokenStream.addAttribute(TermToBytesRefAttribute.class);
    posIncAtt = tokenStream.addAttribute(PositionIncrementAttribute.class);
    offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
    tokenStream.reset();

    this.tagClusterReducer = tagClusterReducer;
  }

  public void process() throws IOException {

    //a shared pointer to the head used by this method and each Tag instance.
    final TagLL[] head = new TagLL[1];

    MyFstCursor<Long> cursor = null;

    int lastStartOffset = -1;

    while (tokenStream.incrementToken()) {

      //sanity-check that start offsets don't decrease
      if (lastStartOffset > offsetAtt.startOffset())
        throw new IllegalStateException("startOffset must be >= the one before: "+lastStartOffset);
      lastStartOffset = offsetAtt.startOffset();

      //-- If PositionIncrement > 1 then finish all tags
      int posInc = posIncAtt.getPositionIncrement();
      if (posInc < 1) {
        throw new IllegalStateException("term: " + byteRefAtt.getBytesRef().utf8ToString()
            + " analyzed to a token with posinc < 1: "+posInc);
      } else if (posInc > 1) {
        advanceTagsAndProcessClusterIfDone(head, -1);
      }

      //-- Lookup the term id from the next token
      int termId = getTermIdFromByteRef();

      //-- Process tag
      advanceTagsAndProcessClusterIfDone(head, termId);

      //-- Create a new tag and try to advance it
      if (termId >= 0) {

        //determine if the FST has the term as a start state
        // TODO use a cached bitset of starting termIds, which is faster than a failed FST advance which is common
        if (cursor == null)
          cursor = new MyFstCursor<Long>(corpus.getPhrases());
        if (cursor.nextByLabel(termId)) {
          TagLL newTail = new TagLL(head, cursor, offsetAtt.startOffset(), offsetAtt.endOffset(), null);
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

    tokenStream.end();
    tokenStream.close();
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
      throw new IllegalArgumentException("term: " + bytesRef.utf8ToString() + " analyzed to a zero-length token");
    }
    return corpus.lookupTermId(bytesRef);//-1 if not found
  }

  /**
   * Invoked by {@link #process()} for each tag found.  endOffset is always >= the endOffset given in the previous
   * call.
   * @param startOffset The character offset of the original stream where the tag starts.
   * @param endOffset One more than the character offset of the original stream where the tag ends.
   * @param docIdsKey A reference to the matching docIds that can be resolved via {@link #lookupDocIds(long)}.
   */
  protected abstract void tagCallback(int startOffset, int endOffset, long docIdsKey);

  /**
   * Returns a sorted array of integer docIds given the corresponding key.
   * @param docIdsKey The lookup key.
   * @return Not null
   */
  protected IntsRef lookupDocIds(long docIdsKey) {
    return corpus.getDocIdsByPhraseId(docIdsKey);
  }
}
