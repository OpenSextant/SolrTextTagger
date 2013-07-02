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

import org.apache.lucene.util.fst.FST;

import java.io.IOException;

/**
 * Similar to {@link org.apache.lucene.util.fst.FSTEnum} but traverses
 * one int (label) at a time down the tree.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public class MyFstCursor<T> {
  private final FST<T> fst;
  private FST.Arc<T> arc;
  private T output;//we don't update with arc.nextFinalOutput; just prior
  private final FST.BytesReader fstReader;
  //private final FST.Arc<T> scratchArc = new FST.Arc<T>();
  private int len = 0;

  public MyFstCursor(FST<T> fst) {
    this.fst = fst;
    fstReader = fst.getBytesReader(); 
    output = fst.outputs.getNoOutput();
    arc = fst.getFirstArc(new FST.Arc<T>());
  }

  public boolean hasValue() {
    return arc.isFinal();
  }

  public int getKeyLength() { return len; }

  public boolean nextByLabel(int nextLabel) throws IOException {
    if (fst.findTargetArc(nextLabel, arc, arc, fstReader) == null)
      return false;
    //note: arc is internally updated
    len++;
    output = fst.outputs.add(output, arc.output);
    return true;
  }

  public T getValue() {
    if (arc.isFinal())
      return fst.outputs.add(output, arc.nextFinalOutput);
    return null;
  }

}
