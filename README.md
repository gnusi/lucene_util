Get Lucene code
```
 git clone git://git.apache.org/lucene-solr.git trunk; cd trunk; git checkout d156aaf; cd ..
 git clone git://git.apache.org/lucene-solr.git patch; cd patch; git checkout d156aaf; cd ..
```

Prepare benchmark, download test data

Automatic
```
cd util
python src/python/setup.py -download
```

or manual
```
mkdir data
wget -c http://home.apache.org/~mikemccand/enwiki-20120502-lines-1k.txt.lzma
lzma -dk enwiki-20120502-lines-1k.txt.lzma
wget -c http://home.apache.org/~mikemccand/wikimedium500.tasks
cd ../util
python src/python/setup.py
```

Only Java-9 SDK worked for me on Ubuntu: http://www.webupd8.org/2015/02/install-oracle-java-9-in-ubuntu-linux.html

Run benchmark
```
python src/python/localrun.py -source wikimedium10k
```

For benchmark code analysis, ideaIC project files with debug configs are located in ./util/src/.idea

