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

import org.apache.lucene.util.fst.FST;

import java.io.IOException;

interface TagReceiver {
  void receiveTag(TagLL tag);
}

/**
 * This is a Tag -- a startOffset, endOffset, and value.  It's chained to other
 * tags, hence the LL (Linked List).  A Tag starts incomplete with only
 * startOffset, and in an "advancing" state as more words are consumed.  As it
 * advances, it accumulates the longest running sub-tag into endOffset and value
 * (if any).  Other tags starting at subsequent startOffset's are chained to it
 * via advancingLL. Once a tag doesn't advance anymore, it's removed from
 * advancingLL. Prior to that moment, if it's the head of the LL, then it's tag
 * is output (if value is non-null).  There is another aspect to it called a
 * pending tag. If a tag doesn't advance anymore and it is NOT the head, and if
 * value is non-null, then it appends itself to its parent advancingLL into a
 * pendingLL.  When value is output, so are its pending tags.  But pending tags
 * at and below an advancing tag are cleared whenever it reaches a farther
 * sub-tag, since they would otherwise be a sub-tag.
 * <p/>
 * The above description is modified when subTags is true.  For subTags, when
 * "endOffset" and "value" are updated, it is immediately generated to
 * output then cleared.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
class TagLL extends MyFstCursor<Long> {
//extends the cursor to avoid object allocation

  final int startOffset;
  //farthest 'final' arc at
  int endOffset;
  Long value;
  TagLL pendingLL;//non-advancing (pending) tag linked list
  TagLL advancingLL;//advancing (increasing startOffset) tag linked list.

  TagLL(FST<Long> fst, int startOffset) {
    super(fst);
    this.startOffset = startOffset;
  }

  private MyFstCursor<Long> cursor() {
    return this;
  }

  /**
   * Advances this tag with "word" at offset "offset".  Not only is this tag
   * advanced, but it will recursively do so to others in advancingLL.
   *
   * @param parent    The parent advancingLL chain to this tag.  If null then
   *                  this tag is the head.
   * @param word      The next word (FST ord surrogate), possibly -1 which won't
   *                  advance.
   * @param offset    The last character in word's offset in the underlying
   *                  stream. If word is -1 then it's meaningless.
   * @param subOffset The farthest endOffset above this in the advancingLL
   *                  chain. -1 for none.
   * @param subTags   Whether all intermediate tags should be generated.
   * @param output    Where generated tags should go.  @return The tag to take
   *                  this tag's place in the advancingLL chain, pending
   *                  completion of this method. Returning "this" makes no
   *                  change; returning the result of the next
   *                  advancingLL.advance() will remove this tag.
   * @throws IOException
   */
  TagLL advance(TagLL parent, int word, int offset, int subOffset, boolean subTags, TagReceiver output) throws IOException {
    assert word < 0 || subOffset <= offset;
    if (word >= 0 && subOffset == offset) {
      //because our furthest tag is not competitive
      endOffset = -1;
      value = null;
      pendingLL = null;
    }

    if (word >= 0 && cursor().nextByLabel(word)) {
      //---- advances ----

      if ((subTags || subOffset != offset) && cursor().hasValue()) {
        endOffset = offset;
        value = cursor().getValue();
        pendingLL = null;
        if (subTags) {
          output.receiveTag(this);
          endOffset = -1;
          value = null;
        }
      }
      if (advancingLL != null)
        advancingLL = advancingLL.advance(this, word, offset, Math.max(subOffset, endOffset), subTags, output);
      return this;//keeps "this" tag in the advancing linked-list

    } else {
      //---- does not advance ----

      if (parent == null) {
        //head:
        if (value != null)
          output.receiveTag(this);
        for (TagLL pendingI = pendingLL; pendingI != null; pendingI = pendingI.pendingLL)
          output.receiveTag(pendingI);

      } else {
        //non-head:
        TagLL lastParentPending = parent;
        //walk to the end
        while (lastParentPending.pendingLL != null)
          lastParentPending = lastParentPending.pendingLL;
        // append this
        lastParentPending.pendingLL = (value != null ? this : pendingLL);

      }

      //(we remove "this" tag from the advancing linked-list)
      if (advancingLL != null)
        return advancingLL.advance(parent, word, offset, Math.max(subOffset, endOffset), subTags, output);
      return null;
    }
  }//advance()

  void addToTail(TagLL tailTag) {
    TagLL i = this;
    while (i.advancingLL != null)
      i = i.advancingLL;
    i.advancingLL = tailTag;
  }
}
