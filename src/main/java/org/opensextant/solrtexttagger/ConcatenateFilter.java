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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.tokenattributes.*;

import java.io.IOException;

/**
 * Concatenate all tokens, separated by a provided character,
 * defaulting to a single space. It always produces exactly one token, and it's designed to be the
 * last token filter in an analysis chain.
 */
public class ConcatenateFilter extends TokenFilter {

  /*
  For a very different approach that could accept synonyms or anything except position gaps (e.g.
  not stopwords),
  consider using o.a.l.analysis.TokenStreamToAutomaton
  with o.a.l.util.automaton.SpecialOperations.getFiniteStrings().
  For gaps (stopwords), we could perhaps index a special token at those gaps and then have the
  tagger deal with them -- also doable.
   */

  private char separator = ' ';

  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
  private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
  private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

  private boolean done;
  private StringBuilder buf = new StringBuilder(128);

  /**
   * Construct a token stream filtering the given input.
   */
  protected ConcatenateFilter(TokenStream input) {
    super(input);
  }

  public void setTokenSeparator(char separator) {
    this.separator = separator;
  }

  @Override
  public void reset() throws IOException {
    input.reset();
    done = false;
  }

  @Override
  public final boolean incrementToken() throws IOException {
    if (done)
      return false;
    done = true;

    buf.setLength(0);
    boolean firstTerm = true;
    while (input.incrementToken()) {
      if (!firstTerm) {
        buf.append(separator);
      }
      //TODO consider indexing special chars when posInc > 1 (stop words). We ignore for now. #13
      buf.append(termAtt);
      firstTerm = false;
    }
    input.end();//call here so we can see end of stream offsets

    termAtt.setEmpty().append(buf);
    //Setting the other attributes ultimately won't have much effect but lets be thorough
    offsetAtt.setOffset(0, offsetAtt.endOffset());
    posIncrAtt.setPositionIncrement(1);
    posLenAtt.setPositionLength(1);//or do we add up the positions?  Probably not used any way.
    typeAtt.setType(ShingleFilter.DEFAULT_TOKEN_TYPE);//"shingle"

    return true;
  }

  @Override
  public void end() throws IOException {
    //we already called input.end() in incrementToken
  }
}
