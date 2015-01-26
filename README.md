# Solr Text Tagger

This project implements a "naive" text tagger based on Lucene / Solr, using
Lucene FST (Finite State Transducer) technology under the hood for remarkable low-memory properties.  It is "naive"
because it does simple text word based substring tagging without consideration
of any natural language context.  It operates on the results of how you
configure text analysis in Lucene and so it's quite flexible to match things
like phonetics for sounds-like tagging if you wanted to.  For more information, see the presentation video/slides referenced below.

## Resources / References

* [Text Tagging with Finite State Transducers (video)](http://www.youtube.com/watch?v=3kQyYbTyXfc) ([slides](http://lucenerevolution.org/wp-content/uploads/2014/08/Text-Tagging-with-Finite-State-Transducers.pdf)) -- a presentation at Lucene Revolution 2013 by David Smiley
* [Fuzzy String Matching with SolrTextTagger](http://sujitpal.blogspot.com/2014/02/fuzzy-string-matching-with.html) -- a blog post by Sujit Pal

Pertaining to Lucene's Finite State Transducers:

* https://docs.google.com/presentation/d/1Z7OYvKc5dHAXiVdMpk69uulpIT6A7FGfohjHx8fmHBU/edit#slide=id.p
* http://blog.mikemccandless.com/2010/12/using-finite-state-transducers-in.html
* http://blog.mikemccandless.com/2011/01/finite-state-transducers-part-2.html

## Contributors:

  * David Smiley (MITRE)

## Build Instructions

The build requires Maven, although an out-dated Ant build file remains.

To compile and run tests, use:

    %> mvn test

To compile, test, and build the jar (placed in target/), use

    %> mvn package


## Configuration

A Solr schema.xml needs 2 things

 * A unique key field  (see <uniqueKey>).
 * A name/lookup field indexed with Shingling or more likely ConcatenateFilter.

Assuming you want to support typical keyword search on the names, you'll index
the names separately in another field with a different field type configuration than the
configuration described here.
The name field's index analyzer needs to end in either Shingling for "partial"
(i.e. sub name phrase) matching, or more likely using ConcatenateFilter for full matching.
Don't do it for the query time analysis. ConcatenateFilter acts similar to shingling but it
concatenates all tokens into one final token with a space separator.

For the indexed name data, the text analysis should result in
consecutive positions <i>(i.e. the position increment of each term must always be
1)</i>.  So, be careful with use of stop words, synonyms, WordDelimiterFilter, and
potentially others.  On the other hand, if the input text
has a position increment greater than one then it is handled properly as if an
unknown word was there.  This is a feature that has largely been overcome in the 1.1 version but it has yet to be ported to 2.x; see [Issue #20, RE the PhraseBuilder](https://github.com/OpenSextant/SolrTextTagger/issues/20)

To make the tagger work as fast as possible, configure the name field with
<i>postingsFormat="Memory";</i> you'll have to add this to <i>solrconfig.xml</i> to use that
advanced Solr feature.

    <codecFactory name="CodecFactory" class="solr.SchemaCodecFactory" />

Here is a sample field type config that should work quite well:

    <fieldType name="tag" class="solr.TextField" positionIncrementGap="100" postingsFormat="Memory"
        omitTermFreqAndPositions="true" omitNorms="true">
      <analyzer type="index">
        <tokenizer class="solr.StandardTokenizerFactory"/>
        <filter class="solr.EnglishPossessiveFilterFactory" />
        <filter class="solr.ASCIIFoldingFilterFactory"/>
        <filter class="solr.LowerCaseFilterFactory" />

        <filter class="org.opensextant.solrtexttagger.ConcatenateFilterFactory" />
      </analyzer>
      <analyzer type="query">
        <tokenizer class="solr.StandardTokenizerFactory"/>
        <filter class="solr.EnglishPossessiveFilterFactory" />
        <filter class="solr.ASCIIFoldingFilterFactory"/>
        <filter class="solr.LowerCaseFilterFactory" />
      </analyzer>
    </fieldType>

A Solr solrconfig.xml needs a special request handler, configured like this.

    <requestHandler name="/tag" class="org.opensextant.solrtexttagger.TaggerRequestHandler">
      <lst name="defaults">
        <str name="field">name_tag</str>
        <str name="fq">NOT name:(of the)</str><!-- filter out -->
      </lst>
    </requestHandler>

 * field: The field that represents the corpus to match on, as described above.
 * fq: (optional) A query that matches the subset of documents for name matching.

## Usage

For tagging, you HTTP POST data to Solr similar to how the ExtractingRequestHandler
(Tika) is invoked.  A request invoked via the "curl" program could look like this:

    curl -XPOST \
      'http://localhost:8983/solr/tag?overlaps=NO_SUB&tagsLimit=5000&fl=*' \
      -H 'Content-Type:text/plain' -d @/mypath/myfile.txt

### The tagger request-time parameters are

 * overlaps: choose the algorithm to determine which overlapping tags should be
 retained, versus being pruned away.  See below...
 * matchText: A boolean indicating whether to return the matched text in the tag
 response.  This will trigger the tagger to fully buffer the input before tagging.
 * tagsLimit: The maximum number of tags to return in the response.  Tagging
 effectively stops after this point.  By default this is 1000.
 * rows: Solr's standard param to say the maximum number of documents to return,
 but defaulting to 10000 for a tag request.
 * skipAltTokens: A boolean flag used to suppress errors that can occur if, for
 example, you enable synonym expansion at query time in the analyzer, which you
 normally shouldn't do. Let this default to false unless you know that such
 tokens can't be avoided.
 * ignoreStopwords: A boolean flag that causes stopwords (or any condition causing positions to
 skip like >255 char words) to be ignored as if it wasn't there. Otherwise, the behavior is to treat
 them as breaks in tagging on the presumption your indexed text-analysis configuration doesn't have
 a StopWordFilter. By default the indexed analysis chain is checked for the presence of a
 StopWordFilter and if found then ignoreStopWords is true if unspecified. You probably shouldn't
 have a StopWordFilter configured and probably won't need to set this param either.
 * xmlOffsetAdjust: A boolean indicating that the input is XML and furthermore that the offsets of
 returned tags should be adjusted as necessary to allow for the client to insert an open and closing
 element at the positions. If it isn't possible to do so then the tag will be omitted. You are
 expected to configure HTMLStripCharFilter in the schema when using this option.
 This will trigger the tagger to fully buffer the input before tagging.
 * htmlOffsetAdjust: Similar to xmlOffsetAdjust except for HTML content that may have various issues
 that would never work with an XML parser. There needn't be a top level element, and some tags
 are known to self-close (e.g. BR). The tagger uses the Jericho HTML Parser for this feature
 (dual LGPL & EPL licensed).
 * nonTaggableTags: (only with htmlOffsetAdjust) Omits tags that would enclose one of these HTML
 elements. Comma delimited, lower-case. For example 'a' (anchor) would be a likely choice so that
 links the application inserts don't overlap other links.
 * fl: Solr's standard param for listing the fields to return.
 * Most other standard parameters for working with Solr response formatting:
 echoParams, wt, indent, etc.

### Options for the "overlaps" parameter

 * ALL: Emit all tags.
 * NO_SUB: Don't emit a tag that is completely within another tag (i.e. no subtag).
 * LONGEST_DOMINANT_RIGHT: Given a cluster of overlapping tags, emit the longest
  one (by character length). If there is a tie, pick the right-most. Remove
  any tags overlapping with this tag then repeat the algorithm to potentially
  find other tags that can be emitted in the cluster.

The output is broken down into two parts, first an array of tags, and then
Solr documents referenced by those tags.  Each tag has the starting character
offset, an ending character (+1) offset, and the Solr unique key field value.
The Solr documents part of the response is Solr's standard search results
format.
