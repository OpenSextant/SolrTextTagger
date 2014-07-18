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
    assertXmlTag("<em>other text</em> start end", true);
  }
}
