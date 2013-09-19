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

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.IntsRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds phrases from {@link Token}s in {@link TokenStream}. This works by
 * evaluating {@link PositionIncrementAttribute} as well as the
 * {@link OffsetAttribute}. <p>
 * Using the {@link PositionLengthAttribute} would be much easier but most of
 * current Solr {@link Analyzer}s do not properly support it.
 * <p>
 *
 * <h3>Performance considerations:</h3>
 *
 * {@link TokenStream}s that do not define any alternate tokens (tokens with
 * <code>posInc == 0</code>) will be processed by calling
 * {@link #create(int, int, int)} once for the first tokens. For all
 * remaining tokens {@link #append(int, int, int)} will be called. This code
 * path does execute fast. Processing such TokenStremas will only create a
 * single instance of {@link Phrase}. <p>
 * For {@link TokenStream} that do define alternate tokens more processing is
 * needed. For each branch (defined by the start offset of a token with a
 * position increment of zero) start positions of existing {@link Phrase}s need
 * to checked. All existing {@link Phrase}s that use this position need to be
 * copied and on the copy the current term need to be set.
 * <p>
 *
 * <h3>Limitations</h3>
 *
 * This class can not support multi token matchings of {@link SynonymFilter}.
 * Those are entries in the synonyms file where a left hand value with
 * <code>n</code> tokens is mapped to a right hand value with more then
 * <code>n</code> tokens (e.g. <code>'DNS => Domain Name Service'</code> or
 * also <code>'DNS, Domain Name Service'</code>). Users are encouraged to
 * reverse all such mappings so that the synonym with the more tokens is on
 * the left hand side (e.g. <code>'Domain Name Service => DNS'</code>). <p>
 * If the PhraseBuilder encounters such cases the
 * {@link #addTerm(int, int, int, int, int)} will throw an
 * {@link UnsupportedTokenException} and ERROR level logging providing similar
 * information will be printed to the log.<p>
 * For more information on this issue please look at the <i>Limitation<i>
 * section of the Blog
 * <a herf="http://blog.mikemccandless.com/2012/04/lucenes-tokenstreams-are-actually.html">
 * Lucene's TokenStreams are actually graphs!</a> by  Michael McCandless.
 * <p>
 * <hr>
 * <b>TODO:</b> This could also directly take the {@link TokenStream} as input
 * and also implement the logic of iterating over the tokens. Currently this is
 * still done in the {@link TaggerFstCorpus}#analyze(..) method.
 * <hr>
 * @author Rupert Westenthaler
 *
 */
class PhraseBuilder {

  private final Logger log = LoggerFactory.getLogger(PhraseBuilder.class);

  /**
   * List with the phrases. This list is sorted by {@link Phrase#pStart}
   */
  protected final List<Phrase> phrases;

  /**
   * Used to memorize the start offset of the first token(s) reported by the
   * TokenStream
   */
  int startOffset = -1;

  /**
   * Internally used by {@link #branch(int, int, int)} and
   * {@link #append(int, int, int)} to temporarily store {@link Phrase} <p>
   * Those methods to use this field to avoid creating new {@link List}
   * instances on each call.<p>
   * Users of this field are expected to call {@link List#clear()} before
   * usage.
   */
  private List<Phrase> tempPhrases;

  /**
   * Creates a new PhraseBuilder instance. Note that this instance should be
   * reused by calling {@link #reset()}.
   *
   * @see #reset()
   */
  public PhraseBuilder() {
    phrases = new ArrayList<Phrase>();
    tempPhrases = new ArrayList<Phrase>();
  }

  /**
   * Creates a new PhraseBuilder instance with the initial capacity.
   * Note that this instance should be reused by calling {@link #reset()}.
   *
   * @see #reset()
   */
  public PhraseBuilder(int initialPathCapacity) {
    phrases = new ArrayList<Phrase>(initialPathCapacity);
    tempPhrases = new ArrayList<Phrase>(initialPathCapacity);
  }

  /**
   * Adds the term to the phrase
   *
   * @param termId the integer termId representing the term.
   * @param start  the start offset for the term
   * @param end    the end offset of the term
   * @param posInc the position increment of the term
   * @param posLen the position length of the term (currently not used)
   * @throws UnsupportedTokenException if the parsed term can not be processed
   */
  public void addTerm(int termId, int start, int end, int posInc, int posLen) {
    if (posInc > 0) { //new token position
      if (phrases.isEmpty()) { // the first token
        startOffset = start; //set the start offset
        create(termId, start, end); //create a new Phrase
      } else { //tokens with posInc > 0 need to be append to existing phrases
        if (!append(termId, start, end)) {
          //this means that we do have multiple tokens for the same span (start
          //and end offset). 
          log.error("Unable to append term[offset: [{},{}], posInc: {}, posLen {}] "
              + "to any phrase.", new Object[]{start, end, posInc, posLen});
          log.error("This is caused by Terms with posInc > 0 but same "
              + "offsets already used by an other token. Please change the "
              + "configuration of your Analyzer chain.");
          log.error("This can be cuased e.g. by:");
          log.error(" > the SynonymFilter with configured multiple word mappings "
              + "(e.g. 'DNS => Domain Name Service'). In such cases it can be "
              + "solved by using the reverse mappings ('Domain Name Service => DNS')");
          log.error(" > Using the WordDelimiterFilter to generate tokens that are "
              + "afterwards processed by the SynonymFilter.");
          log.error(" > Applying the WordDelimiterFilter to tokens where the term. "
              + "length != endOffset - startOffset. In such cases the "
              + "WordDelimiterFilter does correct offset. This can e.g. be cuased "
              + "by the HyphenatedWordsFilter or the EnglishPossessiveFilter. "
              + "Generally it is recommended to use  the WordDelimiterFilter "
              + "directly after the Tokenizer.");
          log.error("For more information see also limitations section of "
              + "http://blog.mikemccandless.com/2012/04/"
              + "lucenes-tokenstreams-are-actually.html");
          throw new UnsupportedTokenException("Encountered Term with "
              + "posInc > 0 but similar offset as existing one (see ERROR "
              + "level loggings for details and help!");
        } //else successfully appended
      }
    } else { //tokens with posInc == 0 are alternatives to existing tokens
      if (start == startOffset) { //if alternative starts at the beginning
        create(termId, start, end); //simple create a new phrase
      } else { //we need to create branches for existing phrases
        branch(termId, start, end);
      }
    }

  }

  /**
   * Creates a new phrase and adds the parsed term and registers it to
   * {@link #phrases}. This method expects that
   * <code>start == {@link #startOffset}</code>
   *
   * @return the created phrase
   */
  private Phrase create(int termId, int start, int end) {
    assert startOffset <= start;
    if (!phrases.isEmpty()) {
      //do not create multiple phrases for the same termId
      int size = phrases.size();
      for (int i = 0; i < size; i++) {
        Phrase phrase = phrases.get(i);
        if (phrase.length() == 1 && phrase.getTerm(0) == termId) {
          return phrase;
        }
      }
    }
    Phrase phrase = new Phrase(8);
    phrases.add(phrase);
    phrase.pStart = start;
    phrase.add(termId, start, end);
    return phrase;
  }

  /**
   * This appends a Token to existing phrases. It is expected to be called if the
   * <code>{@link PositionIncrementAttribute#getPositionIncrement() posInc} > 0 </code>
   * for the parsed term.<p>
   * <b>NOTEs:</b> <ul>
   * <li> This method can not guarantee that the parsed term is
   * appended to any of the phrases. There are cases in that it is not possible
   * to reconstruct phrases based on posInc, start and end offset of terms
   * (e.g. for some {@link SynonymFilter} mappings). This method is able to
   * detect such situations and will refuse to append according tokens. Callers
   * can detect this by <code>false</code> being returned.<p>
   * <li> This method is able to deal with multiple tokens using the same
   * start/end offset as long as there are not multiple possible paths. This
   * means e.g. that synonyms like 'Domain Name Service => DNS' will be
   * correctly handled. However using analyzers generating such token streams
   * are still problematic as they may result in unintended behaviour during
   * search. See the 'Domain Name Service => DNS' example in the limitation
   * section of <a href="http://blog.mikemccandless.com/2012/04/lucenes-tokenstreams-are-actually
   * .html">
   * Lucene's TokenStreams are actually graphs!</a> by Michael McCandless for
   * details why this is the case.<br>
   * </ul>
   *
   * @param termId the termId representing the term
   * @param start  the start index of the term
   * @param end    the end index of the term
   * @return if the term was appended to any of the phrases.
   */
  private boolean append(int termId, int start, int end) {
    int addPos = -1; //stores the char offset of the phrase end where we append
    int branchPos = -1;
    tempPhrases.clear(); //need to clear before usage
    for (int i = phrases.size() - 1; i >= 0; i--) {
      Phrase phrase = phrases.get(i);
      int phraseEnd = phrase.pEnd;
      if (phraseEnd <= start) {
        phrase.add(termId, start, end);
        addPos = phraseEnd;
      } else if (addPos >= 0) { //to avoid unnecessary iterations
        //As tokens can be only appended to phrases ending a a single position.
        //While multiple phrases might use this position we can stop iterating
        //as soon as phrases do no longer have the same pEnd value after the
        //parsed token was appended (addPos >= 0)
        break;
      } else if (phraseEnd == end) {
        //save phrases with same end pos as the parsed term for further 
        //processing (see below)
        tempPhrases.add(phrase);
      } //else uninteresting token
      //maybe we need to call branch later on ...
      // ... so also calculate the possible branch position
      int startPos = phrase.getPos(phrase.length() - 1);
      if (startPos > branchPos && startPos <= start) {
        branchPos = startPos;
      }
    }
    if (addPos < 0) { //not added
      //Two possible reasons:
      //(1) there was an alternate token where this should have been appended
      //but it was removed by an TokenFilter (e.g. "Mr. A.St." could get split
      // to "Mr", "A.St.", "A", "St" and the StopFilterFactory would remove "A".
      //So we might see a Token "ST" with a posInc=1 with no Token to
      //append it as "Mr" is already taken by "A.St.".
      //In this case what we need to do is to find the branchPos for the 
      //removed "A" token and append "ST" directly to that.
      //(2) multiple Tokens with posInc > 0 for tokens with the same
      //span (start/end offset). This is typical the case if a single token
      //in the text is split up to several. Those cases can only be correctly
      //handled if all matching phrases to have the same termId as otherwise
      //we can not reconstruct the correct phrases.

      //First test for case (2)
      boolean isSameSpan = false; //used to check for (2)
      if (!tempPhrases.isEmpty()) {
        int tempSize = tempPhrases.size();
        int addPhraseNum = -1;
        long tId = Long.MIN_VALUE; //keep default outside of int range
        boolean foundMultiple = false;
        for (int i = 0; !foundMultiple && i < tempSize; i++) {
          Phrase phrase = tempPhrases.get(i);
          //check if the last token of this phrase has the same span
          int index = phrase.length() - 1;
          if (phrase.getPos(index) == start) { //check if start also matches
            isSameSpan = true; //there is a Token with the same span
            //but first check for multople phrases with different termIds
            int id = phrase.getTerm(index);
            if (tId == Long.MIN_VALUE) { //store the phraseId
              tId = id;
            } else if (id != tId) {
              //encountered multiple phrases with different termIds
              // ... stop here (error handling is done by calling method
              foundMultiple = true;
            } //else  multiple phrases with same termId
            //do not append immediately (because we should not change the state
            //in case we will find multiple phrases with different termIds
            addPhraseNum++;
            if (addPhraseNum != i) { //write validated back to tempPhrases
              tempPhrases.set(addPhraseNum, phrase);
            } //else the current element is this one
          } //only end matches -> not the same start/end offset -> ignore
        }
        if (!foundMultiple && addPhraseNum >= 0) {
          addPos = end; //just to set addPos > 0
          //now finally append to phrases with same span 
          for (int i = 0; i <= addPhraseNum; i++) {
            tempPhrases.get(i).add(termId, start, end);
          }
        }
      } //no span with the same end offset ... can only be case (1)

      //handle case (1)
      if (!isSameSpan) {
        //NOTE that branchPos might not be available if the removed token
        //was at the begin of the text. So use the start offset of the
        //parsed token if branchPos < 0
        return branch(termId, branchPos < 0 ? start : branchPos, end);
      }
    }
    return addPos >= 0;
  }

  /**
   * Creates branch(es) for an alternate term at the parsed start/end char
   * offsets. Also inserts created {@link Phrase}s to {@link #phrases} keeping
   * elements in {@link #phrases} sorted by {@link Phrase#pStart}. No return
   * value as the state will be stored in the {@link #phrases} collection.<p>
   * Note this method is only called for {@link TokenStream}s with alternate
   * tokens (<code>{@link PositionIncrementAttribute#getPositionIncrement() posInc}
   * == 0</code>). So this rather complex operation will only be executed for
   * labels that do result in such tokens.
   *
   * @param termId the termId to add right after the branch
   * @param start  the start of the term. Also the position of the branch
   * @param end    the end of the term
   * @return <code>true</code> if the termId was successfully processed.
   *         Otherwise <code>false</code>
   */
  private boolean branch(int termId, int start, int end) {
    int size = phrases.size();
    tempPhrases.clear(); //need to clear before using
    boolean create = true; //if not a branch create a new phrase
    //we need to create a branch for all phrases staring before the parsed term
    int i = 0;
    for (; i < size; i++) {
      Phrase phrase = phrases.get(i);
      if (phrase.pStart < start) {
        if (phrase.hasPos(start)) {
          create = false; // this is a branch
          Phrase branch = phrase.clone();
          branch.pStart = start;
          if (branch.set(termId, start, end)) {
            tempPhrases.add(branch);
          } //else no need to branch, because the parsed termId equals
          //the previous one.
        } //else the phrase starts earlier, but does not have a term that ends
        //at the start position of the current one (it skips this position
        //with a term of a posLen > 1). So we do not need to create a branch.
      } else if (phrase.pStart > start) {
        //stop here, because it is the position in phrases where we need to
        //start inserting the creates branches.
        break;
      }
    }
    if (create) {
      return create(termId, start, end) != null;
    } else { //insert branches into phrases
      int bSize = tempPhrases.size();
      if (bSize > 0) { //insert branch(es) in sorted phrases list
        int numSwap = size - i;
        for (int j = 0; j < bSize; j++, i++) {
          if (i < size) {
            //swap the 'j' element of branches with the 'i' of #phrases
            tempPhrases.set(j, phrases.set(i, tempPhrases.get(j)));
          } else {
            phrases.add(tempPhrases.get(j));
          }
        }
        for (; i < size; i++) { //add remaining elements in phrases as we need to
          tempPhrases.add(phrases.get(i)); //append them to have a sorted list
        }
        //finally add Phrases swapped to branches to the end of #phrases
        for (int j = 0; j < numSwap; j++) {
          phrases.add(tempPhrases.get(j));
        }
        //now #phrases is again sorted by Phrase#pStart
      } //else no branch created (branch would have same termId as existing one)
      return true;
    }
  }

  /**
   * Getter for the {@link IntsRef}s of the phrases build from terms parsed to
   * the {@link #addTerm(int, int, int, int, int)} method.
   *
   * @return An array containing the created phrases. Each phrase is represented
   *         by a {@link IntsRef}s of its termIds.
   */
  public IntsRef[] getPhrases() {
    int size = phrases.size();
    IntsRef[] refs = new IntsRef[phrases.size()];
    for (int i = 0; i < size; i++) {
      refs[i] = phrases.get(i).getPath();
    }
    return refs;
  }

  /**
   * Resets this {@link PhraseBuilder} instance to its initial state.
   * This allows to reuse the same instance to build phrases for multiple
   * {@link TokenStream}s.
   */
  public void reset() {
    phrases.clear();
    startOffset = -1;
  }


  /**
   * Private class used to build up a single phrase. An {@link IntsRef} is used
   * to build up the phrase. Words are represented by the integer termId.
   *
   * @author Rupert Westenthaler
   */
  class Phrase implements Cloneable {

    /**
     * The path
     */
    private final IntsRef path;
    /**
     * stores the start offsets of terms contained in this phrase. NOTE that
     * this array only contains as many valid values as the number of
     * termIds in {@link #path}.
     */
    private int[] pos;
    /**
     * the current end offset of the phrase.
     */
    protected int pEnd;

    /**
     * The index at that this phrase was created. This is the char offset where
     * the first term of this phrase is different as of other phrases. This
     * information is required to know when one needs to create alternate
     * phrases for alternate tokens.
     */
    protected int pStart;

    /**
     * Creates a new (empty) path with the given capacity
     */
    Phrase(int initialCapacity) {
      this(new IntsRef(initialCapacity), new int[initialCapacity], -1);
    }

    /**
     * Internally used by {@link #clone()} to construct a new Phrase with
     * data copied from the cloned instance.
     *
     * @param path
     * @param pos
     * @param pEnd
     * @see #clone()
     */
    private Phrase(IntsRef path, int[] pos, int pEnd) {
      this.path = path;
      this.pos = pos;
      this.pEnd = pEnd;
      this.pStart = -1;
    }

    /**
     * Adds a term to the {@link IntsRef} representing the path
     *
     * @param termId the termId to add
     * @param tEnd   the end position of the term
     */
    void add(int termId, int start, int end) {
//      assert start >= pEnd; //the added term MUST be at the end of the Phrase
      path.grow(++path.length); //add an element to the path
      path.ints[path.offset + path.length - 1] = termId;
      if (pos.length < path.length) { //increase pos array size
        pos = ArrayUtil.grow(pos);
      }
      pos[path.length - 1] = start;
      pEnd = end;
    }

    /**
     * Sets the term at the according position of the phrase. Also removes
     * all follow up elements from the phrase
     *
     * @param termId the term represented by the termId
     * @param start  the start offset of the term. Will be used to determine the
     *               correct position within the phrase
     * @param end    the end offset of the term.
     */
    boolean set(int termId, int start, int end) {
      assert start < pEnd;
      //backward lookup as we expect to replace the last element most of the time
      for (int idx = path.length - 1; idx >= 0; idx--) {
        if (pos[idx] <= start) { //found the index
          int oldTermId = path.ints[path.offset + idx];
          if (oldTermId != termId) {
            path.ints[path.offset + idx] = termId;
            path.length = idx + 1;
            pEnd = end; //update the end
            return true;
          } else {
            return false;
          }
        }
      }
      //save guard
      throw new IllegalStateException("Should be unreachable. Please report as "
          + "a bug!");
    }

    int length() {
      return path.length;
    }

    /**
     * Checks if a term in this phrase has the given start position
     *
     * @param pos the position to check
     * @return <code>true</code> if such a term exists. Otherwise <code>false</code>
     */
    boolean hasPos(int pos) {
      //search backwards in pos array as we expect this method will ask about
      //the last term in the phrase. Only in rare cases about an earlier.
      for (int idx = path.length - 1; idx >= 0; idx--) {
        int value = this.pos[idx];
        if (value == pos) {
          return true;
        }
        if (value < pos) {
          return false;
        }
      }
      return false;
    }

    /**
     * Returns the start offset of the term at the parsed index within the
     * phrase.
     *
     * @param index the index
     * @return
     */
    int getPos(int index) {
      return pos[index];
    }

    /**
     * Gets the termId of a term in the phrase
     *
     * @param index the index of the element
     * @return the termId
     */
    int getTerm(int index) {
      return path.ints[path.offset + index];
    }

    /**
     * The {@link IntsRef} representing this path
     *
     * @return
     */
    public IntsRef getPath() {
      return path;
    }

    /**
     * The end position of this path
     *
     * @return
     */
    public int getEnd() {
      return pEnd;
    }

    /**
     * Clones the path and adds the clone with {@link PhraseBuilder#phrases}
     */
    @Override
    public Phrase clone() {
      //clone the array as IntsRef#clone() does not!
      int[] refClone = new int[path.ints.length - path.offset]; //use same capacity
      System.arraycopy(path.ints, path.offset, refClone, 0, path.length); //copy data
      //also clone the positions of the path
      int[] posClone = new int[pos.length];
      System.arraycopy(pos, 0, posClone, 0, path.length); //only path.length items
      return new Phrase(new IntsRef(refClone, 0, path.length), posClone, pEnd);
    }
  }
}