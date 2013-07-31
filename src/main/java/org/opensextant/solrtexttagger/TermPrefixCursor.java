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

import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;

/**
 * Cursor into the terms that advances by prefix.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
class TermPrefixCursor {

  static final byte SEPARATOR_CHAR = ' ';

  private final TermsEnum termsEnum;
  private final Bits liveDocs;
  BytesRef prefixBuf;
  DocsEnum docsEnum;

  TermPrefixCursor(TermsEnum termsEnum, Bits liveDocs) {
    this.termsEnum = termsEnum;
    this.liveDocs = liveDocs;
  }

  /** Appends the separator char (if not the first) plus the given word to the prefix buffer,
   * then seeks to it. If false is returned, then the advance failed, after which this
   * cursor should be considered void.  The {{word}} BytesRef is considered temporary. */
  boolean advance(BytesRef word) throws IOException {
    if (prefixBuf == null) { // first advance
      prefixBuf = word;//temporary; don't copy it unless we have to
      if (seekPrefix()) {//... and we have to
        prefixBuf = new BytesRef(64);
        prefixBuf.copyBytes(word);
        return true;
      } else {
        prefixBuf = null;//just to be darned sure 'word' isn't referenced here
        return false;
      }

    } else { // subsequent advance
      //append to existing
      prefixBuf.grow(1 + word.length);
      prefixBuf.bytes[prefixBuf.length++] = SEPARATOR_CHAR;
      prefixBuf.append(word);
      return seekPrefix();
    }
  }

  /** Seeks to prefixBuf or the next prefix of it. Sets docsEnum. **/
  private boolean seekPrefix() throws IOException {
    TermsEnum.SeekStatus seekStatus = termsEnum.seekCeil(prefixBuf);

    docsEnum = null;//can't re-use :-(
    switch (seekStatus) {
      case END:
        return false;

      case FOUND:
        docsEnum = termsEnum.docs(liveDocs, docsEnum, DocsEnum.FLAG_NONE);
        if (liveDocs == null)//then docsEnum is guaranteed to match docs
          return true;

        //need to verify there are indeed docs, which might not be so when there is a filter
        if (docsEnum.nextDoc() != DocsEnum.NO_MORE_DOCS) {
          docsEnum = termsEnum.docs(liveDocs, docsEnum, DocsEnum.FLAG_NONE);//reset
          return true;
        }
        //Pretend we didn't find it; go to next term
        docsEnum = null;
        if (termsEnum.next() == null) { // case END
          return false;
        }
        //fall through to NOT_FOUND

      case NOT_FOUND:
        //termsEnum must start with prefixBuf to continue
        BytesRef teTerm = termsEnum.term();

        if (teTerm.length > prefixBuf.length) {
          for (int i = 0; i < prefixBuf.length; i++) {
            if (prefixBuf.bytes[prefixBuf.offset + i] != teTerm.bytes[teTerm.offset + i])
              return false;
          }
          if (teTerm.bytes[teTerm.offset + prefixBuf.length] != SEPARATOR_CHAR)
            return false;
          return true;
        }
        return false;
    }
    throw new IllegalStateException(seekStatus.toString());
  }

  /** should only be called after advance* returns true */
  DocsEnum getDocsEnum() {
    return docsEnum;
  }
}
