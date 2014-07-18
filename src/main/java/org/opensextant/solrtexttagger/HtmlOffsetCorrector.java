package org.opensextant.solrtexttagger;

import net.htmlparser.jericho.EndTag;
import net.htmlparser.jericho.EndTagType;
import net.htmlparser.jericho.Segment;
import net.htmlparser.jericho.StartTag;
import net.htmlparser.jericho.StartTagType;
import net.htmlparser.jericho.StreamedSource;
import net.htmlparser.jericho.Tag;
import sun.font.LayoutPathImpl;

/**
 * Corrects offsets to adjust for HTML formatted data. The goal is such that the caller should be
 * able to insert a start HTML tag at the start offset and a corresponding end HTML tag at the end
 * offset of the tagger, and have it be valid HTML (assuming it was "valid" in the first place).
 * See {@link #correctPair(int, int)}.
 *
 * This will work on HTML that has numerous problems that browsers deal with, as well as XML.
 *
 * Not thread-safe.
 */
public class HtmlOffsetCorrector extends OffsetCorrector {

  /**
   * Initialize based on the document text.
   *
   * @param docText non-null structured content.
   */
  protected HtmlOffsetCorrector(String docText) {
    super(docText);

    int tagCounter = 1;//document implicit tag, and counting
    int thisTag = 0;//document implicit tag

    tagInfo.add(-1);//parent
    tagInfo.add(-1, 0);//StartTag
    tagInfo.add(docText.length(), docText.length()+1);//EndTag
    parentChangeOffsets.add(-1);
    parentChangeIds.add(thisTag);

    StreamedSource source = new StreamedSource(docText);
    source.setCoalescing(false);

    for (Segment segment : source) {
      if (segment instanceof Tag) {
        Tag tag = (Tag) segment;
        if (tag.getTagType() == StartTagType.NORMAL) {
          final StartTag startTag = (StartTag) tag;
          if (!startTag.isEmptyElementTag() && !startTag.isEndTagForbidden()) {//e.g. not "<br>"
            tagInfo.ensureCapacity(tagInfo.size() + 5);
            final int parentTag = thisTag;
            tagInfo.add(parentTag);
            tagInfo.add(tag.getBegin(), tag.getEnd());
            tagInfo.add(-1, -1);//these 2 will be populated when we get to the close tag
            thisTag = tagCounter++;

            parentChangeOffsets.add(tag.getBegin());
            parentChangeIds.add(thisTag);
          }
        } else if (tag.getTagType() == EndTagType.NORMAL) {
          tagInfo.set(5 * thisTag + 3, tag.getBegin());
          tagInfo.set(5 * thisTag + 4, tag.getEnd());
          thisTag = getParentTag(thisTag);

          parentChangeOffsets.add(tag.getEnd());
          parentChangeIds.add(thisTag);
        }
      }
      //else we don't care
    }//for segment

    parentChangeOffsets.add(docText.length()+1);
    parentChangeIds.add(-1);
  }
}
