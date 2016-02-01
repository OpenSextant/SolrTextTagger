First, understand you must use a version of this "SolrTextTagger" that is compatible with Solr.
Unfortunately, Solr (more often actually Lucene) makes small changes that necessitate an adjustment
in the tagger thus requiring more tagger releases that often have no additional features.  
View the [CHANGES.md](CHANGES.md) file for information on what versions are compatible with what Solr versions.

# Get Java

Get Java, preferably the JDK, AKA the Java SE Development Kit which includes a compiler and other 
useful tools.  I'll assume v1.8, the latest version.  If you already have v1.7, that's fine but be 
aware Solr 6 requires Java v1.8.  There are multiple ways to get Java, including multiple vendors.  
Try [Oracle's download page](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html).  
If you just have the Java "JRE" (no compiler) then that's probably fine.

# Get Apache Solr

Go to [Solr's download page](http://www.apache.org/dyn/closer.lua/lucene/solr/) and download either the
".zip" or the ".tgz" depending on which you prefer, then expand it.  We'll call the expanded directory
SOLR_DIST_DIR.  As of this writing, the latest version is v5.4.1.

# Get the SolrTextTagger

The OpenSextant SolrTextTagger is a plug-in to Apache Solr.  A Plug-in is a '.jar' file (possibly 
requiring other dependent '.jar' files) that is placed somewhere that Solr will see it.  To get the 
text tagger's Jar, you can either download a 
[pre-built one](http://search.maven.org/#search%7Cga%7C1%7Ca%3A%22solr-text-tagger%22) from Maven 
central if it's an official release, or build it yourself if you have a Java compiler and Maven.  
There's also a "SNAPSHOT" (unreleased) 'jar' on Sonatype's maven repository.  
You can find that [here](https://oss.sonatype.org/content/repositories/snapshots/org/opensextant/solr-text-tagger/2.3-SNAPSHOT/).  
Remember to consult [CHANGES.md](CHANGES.md) on which version to use based on which Solr version you chose.  (Hint: you'll need 2.3 if you are running Solr 5.4 or 5.3).

Optional: If you intend to use the `htmlOffsetAdjust` option then you'll need to get the Jericho 
HTML parser too, such as from Maven central. 

## Install the Tagger

The easiest method is simply to put the '.jar' file into SOLR_DIST_DIR/server/solr/lib/.  The
lib dir won't exist initially so create it.
If you need Jericho too then put it here as well.

# Run Solr

Start Solr on port 8983 (Solr's default port):

    bin/solr start

# Create and Configure a Solr Collection

Note that there are 2 ways we could go about this.  Solr's classic approach involves editing some 
config files (schema.xml, solrconfig.xml), which I might have pre-created for these quick-start instructions.  
The newer approach is to use Solr's API to modify the configuration.  We'll choose the latter, even 
though I'm most fond of the former.

Create a Solr collection named "geonames".  Since we don't specify a configuration template (-d) we 
get a so-called "data-driven" configuration.  It's good for experimentation and getting going fast 
but not for production or being optimal.

    bin/solr create -c geonames

## Configuring

We need to configure the schema first.  The "data driven" mode we're using allows us to keep this step fairly
minimal -- we just need to declare a field type, 2 fields, and a copy-field.
The critical part up-front is to define the "tag" field type.  There are many many ways to configure
text analysis; and we're not going to get into those choices here.  But an important bit is the
ConcatenateFilterFactory at the end of the index analyzer chain.  Another important bit for
performance is postingsFormat=Memory (resulting in compact FST based in-memory data structures vs. 
going to disk every time).

Schema configuration:

````
curl -X POST -H 'Content-type:application/json'  http://localhost:8983/solr/geonames/schema -d '{
  "add-field-type":{
    "name":"tag",
    "class":"solr.TextField",
    "postingsFormat":"Memory",
    "omitNorms":true,
    "indexAnalyzer":{
      "tokenizer":{ 
         "class":"solr.StandardTokenizerFactory" },
      "filters":[
        {"class":"solr.EnglishPossessiveFilterFactory"},
        {"class":"solr.ASCIIFoldingFilterFactory"},
        {"class":"solr.LowerCaseFilterFactory"},
        {"class":"org.opensextant.solrtexttagger.ConcatenateFilterFactory"}
      ]},
    "queryAnalyzer":{
      "tokenizer":{ 
         "class":"solr.StandardTokenizerFactory" },
      "filters":[
        {"class":"solr.EnglishPossessiveFilterFactory"},
        {"class":"solr.ASCIIFoldingFilterFactory"},
        {"class":"solr.LowerCaseFilterFactory"}
      ]}
    },

  "add-field":{ "name":"name",     "type":"text_general"},
  
  "add-field":{ "name":"name_tag", "type":"tag",          "stored":false },
  
  "add-copy-field":{ "source":"name", "dest":[ "name_tag" ]}
}'
````

Configure a custom Solr Request Handler:

````
curl -X POST -H 'Content-type:application/json' http://localhost:8983/solr/geonames/config -d '{
  "add-requesthandler" : {
    "name": "/tag",
    "class":"org.opensextant.solrtexttagger.TaggerRequestHandler",
    "defaults":{ "field":"name_tag" }
  }
}'
````

# Load Some Sample Data

We'll go with some Geonames.org data in CSV format.  Solr is quite flexible in loading data in a 
variety of formats.  This [cities1000.zip](http://download.geonames.org/export/dump/cities1000.zip) 
should be almost 7MB file expanding to a cities1000.txt file around 22.2MB containing 145k lines, 
each a city in the world of at least 1000 population.

````
curl -X POST --data-binary @/path/to/cities1000.txt -H 'Content-type:application/csv' \
  'http://localhost:8983/solr/geonames/update?commit=true&optimize=true&separator=%09&encapsulator=%00&fieldnames=id,name,,alternative_names,latitude,longitude,,,countrycode,,,,,,population,elevation,,timezone,lastupdate'
````

That might take around 35 seconds; it depends.  It can be a lot faster if the schema were tuned
to only have what we truly need (no text search if not needed).

In that command we said optimize=true to put the index in a state that will tmake tagging faster.
The encapsulator=%00 is a bit of a hack to disable the default double-quote.

# Tag Time!

This is a trivial example tagging a small piece of text.  For more options, see the Usage section
in the readme.
  
````
curl -X POST \
  'http://localhost:8983/solr/geonames/tag?overlaps=NO_SUB&tagsLimit=5000&fl=id,name,countrycode&wt=json&indent=on' \
  -H 'Content-Type:text/plain' -d 'Hello New York City'
````

The response should be this (the QTime may vary):
````
{
  "responseHeader":{
    "status":0,
    "QTime":1},
  "tagsCount":1,
  "tags":[[
      "startOffset",6,
      "endOffset",19,
      "ids",["5128581"]]],
  "response":{"numFound":1,"start":0,"docs":[
      {
        "id":"5128581",
        "name":["New York City"],
        "countrycode":["US"]}]
  }}
````
