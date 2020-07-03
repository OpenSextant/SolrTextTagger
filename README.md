# Solr Text Tagger

This project implements a "naive" text tagger based on Apache Lucene / Solr, using
Lucene FST (Finite State Transducer) technology under the hood for remarkable low-memory properties.
It is "naive" because it does simple text word based substring tagging without consideration
of any natural language context.  It operates on the results of how you
configure text analysis in Lucene and so it's quite flexible to match things
like phonetics for sounds-like tagging if you wanted to.  For more information, see the presentation
video/slides referenced below.

The tagger can be used for finding entities/concepts in large text, or for doing likewise in queries
to enhance query-understanding.

For a list of changes with version of this tagger, to include Solr & Java version compatibility, 
see [CHANGES.md](CHANGES.md)

### Note: the STT is included in Apache Solr 7.4.0 !!!

Solr 7.4.0 now includes the Solr Text Tagger.  It's [documented in the Solr Reference Guide](https://builds.apache.org/job/Solr-reference-guide-master/javadoc/the-tagger-handler.html).  As-such, you likely should just use the one in Solr and not the one here.  That said, `htmlOffsetAdjust` is not implemented there.  Issues #82 and #81 document some information about the differences and contain further links.

## Resources / References

* [SoDA](https://github.com/elsevierlabs-os/soda) "Solr Dictionary Annotator" is an open-source system that uses this tagger extensively.  You might want to use that instead of the tagger directly.  In addition to more features add on top of the tagger, it has extensive cloud scaling documentation.
* [How-To blog post by Mikołaj Kania](http://mikolajkania.com/2017/03/30/extract-entities-with-solr-text-tagger/)
* [Dictionary Based Annotation at scale with Spark, SolrTextTagger, and OpenNLP (video)](https://www.youtube.com/watch?v=gOe0aYAS8Do)
    ([slides](http://www.slideshare.net/sujitpal/sseu-2015soda))
    -- a presentation by Sujit Pal at Spark Summit Europe 2015
* [Text Tagging with Finite State Transducers (video)](http://www.youtube.com/watch?v=3kQyYbTyXfc)
    ([slides](http://lucenerevolution.org/wp-content/uploads/2014/08/Text-Tagging-with-Finite-State-Transducers.pdf)) -- a presentation at Lucene Revolution 2013 by David Smiley  (first release about the tagger)
* [Fuzzy String Matching with SolrTextTagger](http://sujitpal.blogspot.com/2014/02/fuzzy-string-matching-with.html) -- a blog post by Sujit Pal
* [Tulip](http://dl.acm.org/citation.cfm?id=2634351) -- a winner of the [ERD'14 challenge](https://pdfs.semanticscholar.org/91cf/c37d4853bb7214d18ca091f9bfede8b301a0.pdf) uses the Text Tagger.

Pertaining to Lucene's Finite State Transducers:

* https://docs.google.com/presentation/d/1Z7OYvKc5dHAXiVdMpk69uulpIT6A7FGfohjHx8fmHBU/edit#slide=id.p
* http://blog.mikemccandless.com/2010/12/using-finite-state-transducers-in.html
* http://blog.mikemccandless.com/2011/01/finite-state-transducers-part-2.html

## Contributors:

  * David Smiley
  * Rupert Westenthaler   (notably the PhraseBuilder in the 1.1 branch)

## Quick Start

See the [QUICK_START.md](QUICK_START.md) file for a set of instructions to get you going ASAP.

## Build Instructions

The build requires Java (v8 or v9) and Maven.

To compile and run tests, use:

    %> mvn test

To compile, test, and build the jar (placed in target/), use

    %> mvn package

## Configuration

A Solr schema.xml needs 2 things

 * A unique key field  (see `<uniqueKey>`).  Setting docValues=true on this field is recommended.
 * A name/lookup field indexed with Shingling or more likely ConcatenateFilter.

If you want to support typical keyword search on the names, not just tagging, then index
the names in an additional field with a typical analysis configuration to your preference.

For tagging, the name field's index analyzer needs to end in either shingling for "partial"
(i.e. sub name phrase) matching of a name, or more likely using ConcatenateFilter for 
complete name matching.  ConcatenateFilter acts similar to shingling but it
concatenates all tokens into one final token with a space separator.
The query time analysis should _not_ have Shingling or ConcatenateFilter.

Prior to shingling or the ConcatenateFilter, preceding text analysis should result in
consecutive positions <i>(i.e. the position increment of each term must always be
1)</i>.  As-such, Synonyms and some configurations of WordDelimiterFilter are not supported. 
On the other hand, if the input text
has a position increment greater than one (e.g. stop word) then it is handled properly as if an
unknown word was there.  Support for synonyms or any other filters producing posInc=0 is a feature
that has largely been overcome in the 1.1 version but it has yet to be ported to 2.x; see
[Issue #20, RE the PhraseBuilder](https://github.com/OpenSextant/SolrTextTagger/issues/20)

To make the tagger work as fast as possible, configure the name field with
<i>postingsFormat="FST50";</i>.  In doing so, all the terms/postings are placed into an efficient FST
data structure.

Here is a sample field type config that should work quite well:

    <fieldType name="tag" class="solr.TextField" positionIncrementGap="100" postingsFormat="FST50"
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
        <str name="fq">PUT SOME SOLR QUERY HERE; OPTIONAL</str><!-- filter out -->
      </lst>
    </requestHandler>

 * `field`: The field that represents the corpus to match on, as described above.
 * `fq`: (optional) A query that matches a subset of documents for name matching.

Also, to enable custom so-called postings formats, ensure that your solrconfig.xml has a
codecFactory defined like this:

    <codecFactory name="CodecFactory" class="solr.SchemaCodecFactory" />

## Usage

For tagging, you HTTP POST data to Solr similar to how the ExtractingRequestHandler
(Tika) is invoked.  A request invoked via the "curl" program could look like this:

    curl -XPOST \
      'http://localhost:8983/solr/collection1/tag?overlaps=NO_SUB&tagsLimit=5000&fl=*' \
      -H 'Content-Type:text/plain' -d @/mypath/myfile.txt

### The tagger request-time parameters are

 * `overlaps`: choose the algorithm to determine which overlapping tags should be
 retained, versus being pruned away.  Options are:
  * `ALL`: Emit all tags.
  * `NO_SUB`: Don't emit a tag that is completely within another tag (i.e. no subtag).
  * `LONGEST_DOMINANT_RIGHT`: Given a cluster of overlapping tags, emit the longest
  one (by character length). If there is a tie, pick the right-most. Remove
  any tags overlapping with this tag then repeat the algorithm to potentially
  find other tags that can be emitted in the cluster.
 * `matchText`: A boolean indicating whether to return the matched text in the tag
 response.  This will trigger the tagger to fully buffer the input before tagging.
 * `tagsLimit`: The maximum number of tags to return in the response.  Tagging
 effectively stops after this point.  By default this is 1000.
 * `rows`: Solr's standard param to say the maximum number of documents to return,
 but defaulting to 10000 for a tag request.
 * `skipAltTokens`: A boolean flag used to suppress errors that can occur if, for
 example, you enable synonym expansion at query time in the analyzer, which you
 normally shouldn't do. Let this default to false unless you know that such
 tokens can't be avoided.
 * `ignoreStopwords`: A boolean flag that causes stopwords (or any condition causing positions to
 skip like >255 char words) to be ignored as if it wasn't there. Otherwise, the behavior is to treat
 them as breaks in tagging on the presumption your indexed text-analysis configuration doesn't have
 a StopWordFilter. By default the indexed analysis chain is checked for the presence of a
 StopWordFilter and if found then ignoreStopWords is true if unspecified. You probably shouldn't
 have a StopWordFilter configured and probably won't need to set this param either.
 * `xmlOffsetAdjust`: A boolean indicating that the input is XML and furthermore that the offsets of
 returned tags should be adjusted as necessary to allow for the client to insert an open and closing
 element at the positions. If it isn't possible to do so then the tag will be omitted. You are
 expected to configure HTMLStripCharFilter in the schema when using this option.
 This will trigger the tagger to fully buffer the input before tagging.
 * `htmlOffsetAdjust`: Similar to xmlOffsetAdjust except for HTML content that may have various issues
 that would never work with an XML parser. There needn't be a top level element, and some tags
 are known to self-close (e.g. BR). The tagger uses the Jericho HTML Parser for this feature
 (ASL & LGPL & EPL licensed).
 * `nonTaggableTags`: (only with htmlOffsetAdjust) Omits tags that would enclose one of these HTML
 elements. Comma delimited, lower-case. For example 'a' (anchor) would be a likely choice so that
 links the application inserts don't overlap other links.
 * `fl`: Solr's standard param for listing the fields to return.
 * Most other standard parameters for working with Solr response formatting:
 `echoParams`, `wt`, `indent`, etc.

### Output

The output is broken down into two parts, first an array of tags, and then
Solr documents referenced by those tags.  Each tag has the starting character
offset, an ending character (+1) offset, and the Solr unique key field value.
The Solr documents part of the response is Solr's standard search results
format.

## Advanced Tips

* For reducing tagging latency even further, consider embedding Solr with
 EmbeddedSolrServer.  See EmbeddedSolrNoSerializeTest.
