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

package org.mitre.solr.tagger;

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

  private final boolean subTags;

  public Tagger(TaggerFstCorpus corpus, Analyzer analyzer, Reader reader,
                boolean subTags) throws IOException {
    this.corpus = corpus;
    tokenStream = analyzer.tokenStream("", reader);
    //termAtt = tokenStream.addAttribute(CharTermAttribute.class);
    byteRefAtt = tokenStream.addAttribute(TermToBytesRefAttribute.class);
    posIncAtt = tokenStream.addAttribute(PositionIncrementAttribute.class);
    offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
    tokenStream.reset();

    this.subTags = subTags;
  }

  public void process() throws IOException {
    final TagReceiver output = new TagReceiver() {
      @Override
      public void receiveTag(TagLL tag) {
        tagCallback(tag.startOffset, tag.endOffset, tag.value);
      }
    };

    TagLL head = null;
    while (tokenStream.incrementToken()) {
      //--Lookup the term id from the next token
      int termId = getTermIdFromByteRef();

      //-- Create and add a tail tag
      if (termId >= 0) {
        //determine if the FST has the term as a start state
        // TODO use a cached bitset of starting termIds so we can avoid creating & adding tailTag
        TagLL tailTag = new TagLL(corpus.getPhrases(), offsetAtt.startOffset());
        if (head == null) {
          head = tailTag;
        } else {
          head.addToTail(tailTag);
        }
      }

      //-- Advance the tags
      if (head != null)
        head = head.advance(null, termId, offsetAtt.endOffset(), -1, subTags, output);

    }//end while incrementToken()

    //--Finish tags in progress
    if (head != null)
      head = head.advance(null, -1, -1, -1, subTags, output);
    assert head == null;

    tokenStream.end();
    tokenStream.close();
  }

  private int getTermIdFromByteRef() {
    byteRefAtt.fillBytesRef();
    BytesRef bytesRef = byteRefAtt.getBytesRef();
    int length = bytesRef.length;
    if (length == 0) {
      throw new IllegalArgumentException("term: " + bytesRef.utf8ToString() + " analyzed to a zero-length token");
    }
    if (posIncAtt.getPositionIncrement() != 1) {
      throw new IllegalArgumentException("term: " + bytesRef.utf8ToString() + " analyzed to a token with posinc != 1");
    }
    return corpus.lookupTermId(bytesRef);
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
