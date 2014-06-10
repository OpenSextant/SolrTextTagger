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

import com.carrotsearch.hppc.IntArrayList;
import com.ctc.wstx.stax.WstxInputFactory;
import org.apache.solr.util.EmptyEntityResolver;
import org.codehaus.stax2.LocationInfo;
import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.XMLStreamReader2;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import java.io.StringReader;
import java.util.Arrays;

/**
 * Corrects offsets to adjust for XML formatted data. The goal is such that the caller should be
 * able to insert a start XML tag at the start offset and a corresponding end XML tag at the end
 * offset of the tagger, and have it be valid XML.  See {@link #correctPair(int, int)}.
 *
 * This will not work on invalid XML.
 *
 * Not thread-safe.
 */
public class XmlOffsetCorrector {

  private static final XMLInputFactory2 XML_INPUT_FACTORY;
  static {
    XML_INPUT_FACTORY = new WstxInputFactory();
    XML_INPUT_FACTORY.setXMLResolver(EmptyEntityResolver.STAX_INSTANCE);//a no-op resolver
    XML_INPUT_FACTORY.configureForSpeed();
  }

  //Data structure requirements:
  // Given a character offset:
  //   * determine what tagId is it's parent.
  //   * determine if it is adjacent to the parent open tag, ignoring whitespace
  //   * determine if it is adjacent to the parent close tag, ignoring whitespace
  // Given a tagId:
  //   * What is it's parent tagId
  //   * What's the char offset of the start and end of the open tag
  //   * What's the char offset of the start and end of the close tag

  /** Document text. */
  private final String docText;

  /** Array of tag info comprised of 5 int fields:
   *    [int parentTag, int openStartOff, int openEndOff, int closeStartOff, int closeEndOff].
   * It's size indicates how many tags there are. Tag's are ID'ed sequentially from 0. */
  private final IntArrayList tagInfo;

  /** offsets of parent tag id change (ascending order) */
  private final IntArrayList parentChangeOffsets;
  /** tag id; parallel array to parentChangeOffsets */
  private final IntArrayList parentChangeIds;

  private final int[] offsetPair = new int[] { -1, -1};//non-thread-safe state

  /**
   * Initialize based on the document text.
   * @param docText non-null XML content.
   * @throws XMLStreamException If there's a problem parsing the XML.
   */
  public XmlOffsetCorrector(String docText) throws XMLStreamException {
    this.docText = docText;
    final int guessNumElements = Math.max(docText.length() / 20, 4);

    tagInfo = new IntArrayList(guessNumElements * 5);
    parentChangeOffsets = new IntArrayList(guessNumElements * 2);
    parentChangeIds = new IntArrayList(guessNumElements * 2);

    int tagCounter = 0;
    int thisTag = -1;

    //note: we *could* add a virtual outer tag to guarantee all text is in the context of a tag,
    // but we shouldn't need to because there is no findable text outside the top element.

    final XMLStreamReader2 xmlStreamReader =
            (XMLStreamReader2) XML_INPUT_FACTORY.createXMLStreamReader(new StringReader(docText));

    while (xmlStreamReader.hasNext()) {
      int eventType = xmlStreamReader.next();
      switch (eventType) {
        case XMLEvent.START_ELEMENT: {
          tagInfo.ensureCapacity(tagInfo.size() + 5);
          final int parentTag = thisTag;
          final LocationInfo info = xmlStreamReader.getLocationInfo();
          tagInfo.add(parentTag);
          tagInfo.add((int) info.getStartingCharOffset(), (int) info.getEndingCharOffset());
          tagInfo.add(-1, -1);//these 2 will be populated when we get to the close tag
          thisTag = tagCounter++;

          parentChangeOffsets.add((int) info.getStartingCharOffset());
          parentChangeIds.add(thisTag);
          break;
        }
        case XMLEvent.END_ELEMENT: {
          final LocationInfo info = xmlStreamReader.getLocationInfo();
          tagInfo.set(5 * thisTag + 3, (int) info.getStartingCharOffset());
          tagInfo.set(5 * thisTag + 4, (int) info.getEndingCharOffset());
          thisTag = getParentTag(thisTag);

          parentChangeOffsets.add((int) info.getEndingCharOffset());
          parentChangeIds.add(thisTag);
          break;
        }
        default: //do nothing
      }
    }
  }

  /** Corrects the start and end offset pair. It will return null if it can't
   * due to a failure to keep the offsets balance-able.
   * The start (left) offset is pulled left as needed over whitespace and opening tags. The end
   * (right) offset is pulled right as needed over whitespace and closing tags. It's returned as
   * a 2-element array.
   * <p />Note that the returned array is internally reused; just use it to examine the response.
   */
  public int[] correctPair(int leftOffset, int rightOffset) {
    rightOffset = correctEndOffsetForCloseElement(rightOffset);

    int startTag = lookupTag(leftOffset);
    //offsetPair[0] = Math.max(offsetPair[0], getOpenStartOff(startTag));
    int endTag = lookupTag(rightOffset);
    //offsetPair[1] = Math.min(offsetPair[1], getCloseStartOff(endTag));

    // Find the ancestor tag enclosing offsetPair.  And bump out left offset along the way.
    int iTag = startTag;
    for (; !tagEnclosesOffset(iTag, rightOffset); iTag = getParentTag(iTag)) {
      //Ensure there is nothing except whitespace thru OpenEndOff
      int tagOpenEndOff = getOpenEndOff(iTag);
      if (hasNonWhitespace(tagOpenEndOff, leftOffset))
        return null;
      leftOffset = getOpenStartOff(iTag);
    }
    final int ancestorTag = iTag;
    // Bump out rightOffset until we get to ancestorTag.
    for (iTag = endTag; iTag != ancestorTag; iTag = getParentTag(iTag)) {
      //Ensure there is nothing except whitespace thru CloseStartOff
      int tagCloseStartOff = getCloseStartOff(iTag);
      if (hasNonWhitespace(rightOffset, tagCloseStartOff))
        return null;
      rightOffset = getCloseEndOff(iTag);
    }

    offsetPair[0] = leftOffset;
    offsetPair[1] = rightOffset;
    return offsetPair;
  }

  /** Correct endOffset for closing element at the right side.  E.g. offsetPair might point to:
   * <pre>
   *   foo&lt;/tag&gt;
   * </pre>
   * and this method pulls the end offset left to the '&lt;'. This is necessary for use with
   * {@link org.apache.lucene.analysis.charfilter.HTMLStripCharFilter}.
   *
   * See https://issues.apache.org/jira/browse/LUCENE-5734 */
  private int correctEndOffsetForCloseElement(int endOffset) {
    if (docText.charAt(endOffset-1) == '>') {
      final int newEndOffset = docText.lastIndexOf('<', endOffset - 2);
      if (newEndOffset > offsetPair[0])//just to be sure
        return newEndOffset;
    }
    return endOffset;
  }

  private boolean hasNonWhitespace(int start, int end) {
    for (int i = start; i < end; i++) {
      if (!Character.isWhitespace(docText.charAt(i)))
        return true;
    }
    return false;
  }

  private boolean tagEnclosesOffset(int tag, int off) {
    return off >= getOpenStartOff(tag) && off < getCloseEndOff(tag);
  }

  private int getParentTag(int tag) { return tagInfo.get(tag * 5 + 0); }
  private int getOpenStartOff(int tag) { return tagInfo.get(tag * 5 + 1); }
  private int getOpenEndOff(int tag) { return tagInfo.get(tag * 5 + 2); }
  private int getCloseStartOff(int tag) { return tagInfo.get(tag * 5 + 3); }
  private int getCloseEndOff(int tag) { return tagInfo.get(tag * 5 + 4); }

  private int lookupTag(int off) {
    int idx = Arrays.binarySearch(parentChangeOffsets.buffer, 0, parentChangeOffsets.size(), off);
    if (idx < 0)
      idx = (-idx - 1) - 1;//round down
    return parentChangeIds.get(idx);
  }

}
