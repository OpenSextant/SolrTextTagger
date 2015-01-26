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

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.util.TokenFilterFactory;

import java.util.Map;

/**
 * @see ConcatenateFilter
 */
public class ConcatenateFilterFactory extends TokenFilterFactory {

  private final String tokenSeparator;

  /**
   * Initialize this factory via a set of key-value pairs.
   */
  public ConcatenateFilterFactory(Map<String, String> args) {
    super(args);
    tokenSeparator = get(args, "tokenSeparator", " ");
    if (tokenSeparator.length() != 1)
      throw new IllegalArgumentException("tokenSeparator should be 1 char: "+tokenSeparator);
    if (!args.isEmpty()) {
      throw new IllegalArgumentException("Unknown parameters: " + args);
    }
  }

  @Override
  public TokenStream create(TokenStream input) {
    ConcatenateFilter filter = new ConcatenateFilter(input);
    filter.setTokenSeparator(tokenSeparator.charAt(0));
    return filter;
  }
}
