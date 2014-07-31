package org.opensextant.solrtexttagger;

import net.htmlparser.jericho.EndTagType;
import net.htmlparser.jericho.Segment;
import net.htmlparser.jericho.StartTag;
import net.htmlparser.jericho.StartTagType;
import net.htmlparser.jericho.StreamedSource;
import net.htmlparser.jericho.Tag;

import java.util.Collections;
import java.util.Set;

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
   * @param nonTaggableTags HTML element names that should not be "taggable" (be a part of any
   *                        tag). These must be lower-case.
   */
  protected HtmlOffsetCorrector(String docText, Set<String> nonTaggableTags) {
    super(docText, nonTaggableTags != null);
    if (nonTaggableTags == null)
      nonTaggableTags = Collections.emptySet();

    int tagCounter = 1;//document implicit tag, and counting
    int thisTag = 0;//document implicit tag

    tagInfo.add(-1);//parent
    tagInfo.add(-1, 0);//StartTag
    tagInfo.add(docText.length(), docText.length()+1);//EndTag
    parentChangeOffsets.add(-1);
    parentChangeIds.add(thisTag);

    StreamedSource source = new StreamedSource(docText);
    source.setCoalescing(false);

    int nonTaggablesInProgress = 0;

    for (Segment segment : source) {
      if (segment instanceof Tag) {
        Tag tag = (Tag) segment;
        if (tag.getTagType() == StartTagType.NORMAL) {
          final StartTag startTag = (StartTag) tag;

          //TODO Consider "implicitly terminating tags", which is dependent on the current tag.

          if (!startTag.isEmptyElementTag() && !startTag.isEndTagForbidden()) {//e.g. not "<br>"
            tagInfo.ensureCapacity(tagInfo.size() + 5);
            final int parentTag = thisTag;
            tagInfo.add(parentTag);
            tagInfo.add(tag.getBegin(), tag.getEnd());
            tagInfo.add(-1, -1);//these 2 will be populated when we get to the close tag
            thisTag = tagCounter++;

            parentChangeOffsets.add(tag.getBegin());
            parentChangeIds.add(thisTag);

            //non-taggable tracking:
            if (nonTaggableTags.contains(tag.getName())) {//always lower-case
              if (nonTaggablesInProgress++ == 0)
                nonTaggableOffsets.add(tag.getBegin());
            }
          }
        } else if (tag.getTagType() == EndTagType.NORMAL) {
          //TODO validate we're closing the tag we think we're closing.
          tagInfo.set(5 * thisTag + 3, tag.getBegin());
          tagInfo.set(5 * thisTag + 4, tag.getEnd());
          thisTag = getParentTag(thisTag);

          parentChangeOffsets.add(tag.getEnd());
          parentChangeIds.add(thisTag);

          //non-taggable tracking:
          if (nonTaggableTags.contains(tag.getName())) {
            if (nonTaggablesInProgress-- == 1)
              nonTaggableOffsets.add(tag.getEnd() - 1);
          }
        }
      }
      //else we don't care
    }//for segment

    parentChangeOffsets.add(docText.length()+1);
    parentChangeIds.add(-1);

    assert nonTaggableTags.isEmpty() || nonTaggableOffsets.size() % 2 == 0;//null or even
  }

}
