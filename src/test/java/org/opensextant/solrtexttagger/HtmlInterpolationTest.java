package org.opensextant.solrtexttagger;

import org.junit.Ignore;
import org.junit.Test;

public class HtmlInterpolationTest extends XmlInterpolationTest {
  @Override
  public void setUp() throws Exception {
    super.setUp();
    baseParams.set("htmlOffsetAdjust", "true");
  }

  @Override
  @Test @Ignore //because in html mode, seemingly everything is valid
  public void testValidatingXml() throws Exception {
  }

  @Override
  @Test @Ignore //because in html mode, seemingly everything is valid
  public void testInvalidXml() throws Exception {
  }

  @Override
  protected void validateXml(String xml) throws Exception {
    //cause this test to *not* try to parse as actual html
  }

  @Test
  public void testHtml() throws Exception {
    buildNames("start end");

    assertXmlTag("<doc>before start <br> end after</doc>", true);//br is assumed empty

    //no wrapping tags:
    assertXmlTag("start end", true);
    assertXmlTag("start end <em>other text</em>", true);
    assertXmlTag("start end<em> other text</em>", true);
    assertXmlTag("<em>other text</em> start end", true);
  }

  @Test
  public void testHtmlNonTaggable() throws Exception {
    baseParams.set("nonTaggableTags","a" + (random().nextBoolean() ? ",sub" : ""));
    buildNames("start end");

    assertXmlTag("start end", true);
    assertXmlTag("start <a>end</a>", false);
    assertXmlTag("<a>start</a> end", false);
    assertXmlTag("<doc><a>before </a>start <br> end<a> after</a></doc>", true);//adjacent
    assertXmlTag("<doc><a>before <a>inner</a> </a>start <br> end<a> after</a></doc>", true);

  }
}
