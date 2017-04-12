package perf;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.shingle.ShingleAnalyzerWrapper;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.LogMergePolicy;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.LineFileDocs;
import org.apache.lucene.util.Version;
import org.apache.lucene.util._TestUtil;

// javac -Xlint:deprecation -cp build/core/classes/java:build/test-framework/classes/java:build/core/classes/test:build/contrib/analyzers/common/classes/java:build/contrib/misc/classes/java perf/Indexer.java perf/LineFileDocs.java

// Usage: dirImpl dirPath analyzer /path/to/line/file numDocs numThreads doFullMerge:yes|no verbose:yes|no ramBufferMB maxBufferedDocs codec doDeletions:yes|no printDPS:yes|no waitForMerges:yes|no mergePolicy doUpdate idFieldUsesPulsingCodec

// EG:
//
// java -Xms2g -Xmx2g -server -classpath ".:build/core/classes/java:build/test-framework/classes/java:build/core/classes/test:build/contrib/analyzers/common/classes/java:build/contrib/misc/classes/java" perf.Indexer MMapDirectory "/q/lucene/indices/wikimedium.3x.nightly.nd27.625M/index" StandardAnalyzer /lucene/data/enwiki-20110115-lines-1k-fixed.txt 27625038 1 no no -1 49774 Lucene40 no no yes LogDocMergePolicy no Memory yes no

public final class Indexer {

  // NOTE: returned array might have dups
  private static String[] randomStrings(int count, Random random) {
    final String[] strings = new String[count];
    int i = 0;
    while(i < count) {
      final String s = _TestUtil.randomRealisticUnicodeString(random);
      if (s.length() >= 7) {
        strings[i++] = s;
      }
    }

    return strings;
  }

  public static void main(String[] args) throws Exception {

    final String dirImpl = args[0];
    final String dirPath = args[1];

    final Directory dir;
    if (dirImpl.equals("MMapDirectory")) {
      dir = new MMapDirectory(new File(dirPath));
    } else if (dirImpl.equals("NIOFSDirectory")) {
      dir = new NIOFSDirectory(new File(dirPath));
    } else if (dirImpl.equals("SimpleFSDirectory")) {
      dir = new SimpleFSDirectory(new File(dirPath));
    } else {
      throw new RuntimeException("unknown directory impl \"" + dirImpl + "\"");
    }
      
    final String analyzer = args[2];
    final Analyzer a;
    if (analyzer.equals("EnglishAnalyzer")) {
      a = new EnglishAnalyzer(Version.LUCENE_31);
    } else if (analyzer.equals("ClassicAnalyzer")) {
      a = new ClassicAnalyzer(Version.LUCENE_30);
    } else if (analyzer.equals("StandardAnalyzer")) {
      a = new StandardAnalyzer(Version.LUCENE_36, CharArraySet.EMPTY_SET);
    } else if (analyzer.equals("ShingleStandardAnalyzer")) {
      a = new ShingleAnalyzerWrapper(new StandardAnalyzer(Version.LUCENE_36, CharArraySet.EMPTY_SET),
                                     2, 2);
    } else {
      throw new RuntimeException("unknown analyzer " + analyzer);
    } 

    final String lineFile = args[3];

    // -1 means all docs in the line file:
    final int docCount = Integer.parseInt(args[4]);
    final int numThreads = Integer.parseInt(args[5]);

    final boolean doFullMerge = args[6].equals("yes");
    final boolean verbose = args[7].equals("yes");

    final double ramBufferSizeMB = Double.parseDouble(args[8]);
    final int maxBufferedDocs = Integer.parseInt(args[9]);

    final String defaultPostingsFormat = args[10];
    final boolean doDeletions = args[11].equals("yes");
    final boolean printDPS = args[12].equals("yes");
    final boolean waitForMerges = args[13].equals("yes");
    final String mergePolicy = args[14];
    final boolean doUpdate = args[15].equals("yes");
    final String idFieldCodec = args[16];
    final boolean addGroupingFields = args[17].equals("yes");
    final boolean useCFS = args[18].equals("yes");

    if (addGroupingFields && docCount == -1) {
      throw new RuntimeException("cannot add grouping fields unless docCount is set");
    }

    System.out.println("Dir: " + dirImpl);
    System.out.println("Index path: " + dirPath);
    System.out.println("Analyzer: " + analyzer);
    System.out.println("Line file: " + lineFile);
    System.out.println("Doc count: " + (docCount == -1 ? "all docs" : ""+docCount));
    System.out.println("Threads: " + numThreads);
    System.out.println("Full merge: " + (doFullMerge ? "yes" : "no"));
    System.out.println("Verbose: " + (verbose ? "yes" : "no"));
    System.out.println("RAM Buffer MB: " + ramBufferSizeMB);
    System.out.println("Max buffered docs: " + maxBufferedDocs);
    System.out.println("Default postings format: " + defaultPostingsFormat);
    System.out.println("Do deletions: " + (doDeletions ? "yes" : "no"));
    System.out.println("Wait for merges: " + (waitForMerges ? "yes" : "no"));
    System.out.println("Merge policy: " + mergePolicy);
    System.out.println("Update: " + doUpdate);
    System.out.println("ID field codec: " + idFieldCodec);
    System.out.println("Add grouping fields: " + (addGroupingFields ? "yes" : "no"));
    System.out.println("Compound file format: " + (useCFS ? "yes" : "no"));
    
    final IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_36, a);

    if (doUpdate) {
      iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
    } else {
      iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
    }

    iwc.setMaxBufferedDocs(maxBufferedDocs);
    iwc.setRAMBufferSizeMB(ramBufferSizeMB);

    final Random random = new Random(17);
    final AtomicInteger groupBlockIndex;
    if (addGroupingFields) {
      IndexThread.group100 = randomStrings(100, random);
      IndexThread.group10K = randomStrings(10000, random);
      IndexThread.group100K = randomStrings(100000, random);
      IndexThread.group1M = randomStrings(1000000, random);
      groupBlockIndex = new AtomicInteger();
    } else {
      groupBlockIndex = null;
    }

    final LogMergePolicy mp;
    if (mergePolicy.equals("LogDocMergePolicy")) {
      mp = new LogDocMergePolicy();
    } else if (mergePolicy.equals("LogByteSizeMergePolicy")) {
      mp = new LogByteSizeMergePolicy();
    } else if (mergePolicy.equals("NoMergePolicy")) {
      final MergePolicy nmp = useCFS ? NoMergePolicy.COMPOUND_FILES : NoMergePolicy.NO_COMPOUND_FILES;
      iwc.setMergePolicy(nmp);
      mp = null;
    } else if (mergePolicy.equals("TieredMergePolicy")) {
      final TieredMergePolicy tmp = new TieredMergePolicy();
      iwc.setMergePolicy(tmp);
      tmp.setMaxMergedSegmentMB(1000000.0);
      tmp.setUseCompoundFile(useCFS);
      tmp.setNoCFSRatio(1.0);
      mp = null;
      //    } else if (mergePolicy.equals("BalancedSegmentMergePolicy")) {
      //      mp = new BalancedSegmentMergePolicy();
    } else {
      throw new RuntimeException("unknown MergePolicy " + mergePolicy);
    }

    if (mp != null) {
      iwc.setMergePolicy(mp);
      mp.setUseCompoundFile(useCFS);
      mp.setNoCFSRatio(1.0);
    }

    // Keep all commit points:
    iwc.setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE);

    /*
    final Codec codec = new Lucene40Codec() {
      @Override
      public PostingsFormat getPostingsFormatForField(String field) {
        if (field.equals("id")) {
          if (idFieldCodec.equals("Pulsing40")) {
            return PostingsFormat.forName("Pulsing40");
          } else if (idFieldCodec.equals("Memory")) {
            return PostingsFormat.forName("Memory");
          } else if (idFieldCodec.equals("Lucene40")) {
            return PostingsFormat.forName("Lucene40");
          } else {
            throw new RuntimeException("unknown id field codec " + idFieldCodec);
          }
        } else {
          return PostingsFormat.forName(defaultPostingsFormat);
        }
      }
    };

    iwc.setCodec(codec);
    */

    System.out.println("IW config=" + iwc);
    final IndexWriter w = new IndexWriter(dir, iwc);

    if (verbose) {
      //InfoStream.setDefault(new PrintStreamInfoStream(System.out));
      w.setInfoStream(System.out);
    }

    final LineFileDocs docs = new LineFileDocs(lineFile, false);

    System.out.println("\nIndexer: start");
    final long t0 = System.currentTimeMillis();
    final Thread[] threads = new Thread[numThreads];
    final AtomicInteger count = new AtomicInteger();
    for(int thread=0;thread<numThreads;thread++) {
      threads[thread] = new IndexThread(w, docs, docCount, count, doUpdate, groupBlockIndex);
      threads[thread].start();
    }
    AtomicBoolean stop = null;
    IngestRatePrinter printer = null;
    if (printDPS) {
      stop = new AtomicBoolean(false);
      printer = new IngestRatePrinter(count, stop);
      printer.start();
    }
    for(int thread=0;thread<numThreads;thread++) {
      threads[thread].join();
    }
    if (printer != null) {
      stop.getAndSet(true);
      printer.join();
    }
    docs.close();

    final long t1 = System.currentTimeMillis();
    System.out.println("\nIndexer: indexing done (" + (t1-t0) + " msec); total " + w.maxDoc() + " docs");
    // if we update we can not tell how many docs
    if (!doUpdate && docCount != -1 && w.maxDoc() != docCount) {
      throw new RuntimeException("w.maxDoc()=" + w.maxDoc() + " but expected " + docCount);
    }

    final long t2;
    if (waitForMerges) {
      w.waitForMerges();
      t2 = System.currentTimeMillis();
      System.out.println("\nIndexer: waitForMerges done (" + (t2-t1) + " msec)");
    } else {
      t2 = System.currentTimeMillis();
    }

    final Map<String,String> commitData = new HashMap<String,String>();
    commitData.put("userData", "multi");
    w.commit(commitData);
    final long t3 = System.currentTimeMillis();
    System.out.println("\nIndexer: commit multi (took " + (t3-t2) + " msec)");

    if (doFullMerge) {
      w.forceMerge(1);
      final long t4 = System.currentTimeMillis();
      System.out.println("\nIndexer: full merge done (took " + (t4-t3) + " msec)");

      commitData.put("userData", "single");
      w.commit(commitData);
      final long t5 = System.currentTimeMillis();
      System.out.println("\nIndexer: commit single done (took " + (t5-t4) + " msec)");
    }

    if (doDeletions) {
      final long t5 = System.currentTimeMillis();
      // Randomly delete 5% of the docs
      final Set<Integer> deleted = new HashSet<Integer>();
      final int maxDoc = w.maxDoc();
      final int toDeleteCount = (int) (maxDoc * 0.05);
      System.out.println("\nIndexer: delete " + toDeleteCount + " docs");
      while(deleted.size() < toDeleteCount) {
        final int id = random.nextInt(maxDoc);
        if (!deleted.contains(id)) {
          deleted.add(id);
          w.deleteDocuments(new Term("id", LineFileDocs.intToID(id)));
        }
      }
      final long t6 = System.currentTimeMillis();
      System.out.println("\nIndexer: deletes done (took " + (t6-t5) + " msec)");

      commitData.put("userData", doFullMerge ? "delsingle" : "delmulti");
      w.commit(commitData);
      final long t7 = System.currentTimeMillis();
      System.out.println("\nIndexer: commit delmulti done (took " + (t7-t6) + " msec)");

      if (doUpdate || w.numDocs() != maxDoc - toDeleteCount) {
        throw new RuntimeException("count mismatch: w.numDocs()=" + w.numDocs() + " but expected " + (maxDoc - toDeleteCount));
      }
    }

    // TODO: delmulti isn't done if doFullMerge is yes: we have to go back and open the multi commit point and do deletes against it:

    /*
    if (doFullMerge) {
      final int maxDoc2 = w.maxDoc();
      final int expected = doDeletions ? maxDoc : maxDoc - toDeleteCount;
      if (maxDoc2 != expected {
        throw new RuntimeException("count mismatch: w.maxDoc()=" + w.maxDoc() + " but expected " + expected);
      }
      final int toDeleteCount2 = (int) (maxDoc2 * 0.05);
      System.out.println("\nIndexer: delete " + toDeleteCount + " docs");
      while(deleted.size() < toDeleteCount) {
        final int id = rand.nextInt(maxDoc);
        if (!deleted.contains(id)) {
          deleted.add(id);
          w.deleteDocuments(new Term("id", LineFileDocs.intToID(id)));
        }
      }
      final long t8 = System.currentTimeMillis();
      System.out.println("\nIndexer: deletes done (took " + (t8-t7) + " msec)");

      commitData.put("userData", "delsingle");
      w.commit(commitData);
      final long t9 = System.currentTimeMillis();
      System.out.println("\nIndexer: commit delsingle done (took " + (t9-t8) + " msec)");
    }
    */

    System.out.println("\nIndexer: at close: " + w.segString());
    final long tCloseStart = System.currentTimeMillis();
    w.close(waitForMerges);
    System.out.println("\nIndexer: close took " + (System.currentTimeMillis() - tCloseStart) + " msec");
    dir.close();
    final long tFinal = System.currentTimeMillis();
    System.out.println("\nIndexer: finished (" + (tFinal-t0) + " msec)");
    System.out.println("\nIndexer: net bytes indexed " + docs.getBytesIndexed());
    System.out.println("\nIndexer: " + (docs.getBytesIndexed()/1024./1024./1024./((tFinal-t0)/3600000.)) + " GB/hour plain text");
  }
  
  private static class IngestRatePrinter extends Thread {

    private final AtomicInteger count;
    private final AtomicBoolean stop;
    public IngestRatePrinter(AtomicInteger count, AtomicBoolean stop){
      this.count = count;
      this.stop = stop;
    }
    
    public void run() {
       long time = System.currentTimeMillis();
       System.out.println("startIngest: " + time);
       final long start = time;
       int lastCount = count.get();
       while(!stop.get()) {
        try {
         Thread.sleep(200);
        } catch(Exception ex) {
        }
        int numDocs = count.get();

        double current = numDocs - lastCount;
        long now = System.currentTimeMillis();
        double seconds = (now-time) / 1000.0d;
        System.out.println("ingest: " + (current / seconds) + " " + (now - start));
        time = now;
        lastCount = numDocs;
       }
    }
  }

  // TODO: is there a pre-existing way to do this!!!
  static Document cloneDoc(Document doc1) {
    final Document doc2 = new Document();
    for(Fieldable f : doc1.getFields()) {
      if (f instanceof NumericField) {
        final NumericField field1 = (NumericField) f;
        final NumericField field2 = new NumericField(f.name());
        if (field1.getDataType() == NumericField.DataType.INT) {
          field2.setIntValue(field1.getNumericValue().intValue());
        } else {
          field2.setLongValue(field1.getNumericValue().intValue());
        }
        doc2.add(field2);
      } else {
        Field field1 = (Field) f;
      
        Field field2 = new Field(field1.name(),
                                 field1.stringValue(),
                                 field1.isStored() ? Field.Store.YES : Field.Store.NO,
                                 Field.Index.toIndex(field1.isIndexed(),
                                                     field1.isTokenized(),
                                                     field1.getOmitNorms()));
        doc2.add(field2);
      }
    }

    return doc2;
  }

  private static class IndexThread extends Thread {
    public static String[] group100;
    public static String[] group100K;
    public static String[] group10K;
    public static String[] group1M;
    private final LineFileDocs docs;
    private final int numTotalDocs;
    private final IndexWriter w;
    private final AtomicInteger count;
    private final AtomicInteger groupBlockIndex;
    private final boolean doUpdate;

    public IndexThread(IndexWriter w, LineFileDocs docs, int numTotalDocs, AtomicInteger count, boolean doUpdate, AtomicInteger groupBlockIndex) {
      this.w = w;
      this.docs = docs;
      this.numTotalDocs = numTotalDocs;
      this.count = count;
      this.doUpdate = doUpdate;
      this.groupBlockIndex = groupBlockIndex;
    }

    @Override
    public void run() {
      final LineFileDocs.DocState docState = docs.newDocState();
      final Field idField = docState.id;
      final long tStart = System.currentTimeMillis();
      Term delTerm = null;
      final Field group100Field;
      final Field group100KField;
      final Field group10KField;
      final Field group1MField;
      final Field groupBlockField;
      final Field groupEndField;
      if (group100 != null) {
        group100Field = new Field("group100", "", Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS);
        docState.doc.add(group100Field);
        group10KField = new Field("group10K", "", Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS);
        docState.doc.add(group10KField);
        group100KField = new Field("group100K", "", Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS);
        docState.doc.add(group100KField);
        group1MField = new Field("group1M", "", Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS);
        docState.doc.add(group1MField);
        groupBlockField = new Field("groupblock", "", Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS);
        docState.doc.add(groupBlockField);
        // Binary marker field:
        groupEndField = new Field("groupend", "x", Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS);
      } else {
        group100Field = null;
        group100KField = null;
        group10KField = null;
        group1MField = null;
        groupBlockField = null;
        groupEndField = null;
      }

      try {
        if (group100 != null) {

          // Add docs in blocks:
          
          final String[] groupBlocks;
          if (numTotalDocs >= 5000000) {
            groupBlocks = group1M;
          } else if (numTotalDocs >= 500000) {
            groupBlocks = group100K;
          } else {
            groupBlocks = group10K;
          }
          final double docsPerGroupBlock = numTotalDocs / (double) groupBlocks.length;

          final List<Document> docsGroup = new ArrayList<Document>();
          while(true) {
            final int groupCounter = groupBlockIndex.getAndIncrement();
            if (groupCounter >= groupBlocks.length) {
              break;
            }
            final int numDocs;
            if (groupCounter == groupBlocks.length-1) {
              // Put all remaining docs in this group
              numDocs = 10000;
            } else {
              // This will toggle between X and X+1 docs,
              // converging over time on average to the
              // floating point docsPerGroupBlock:
              numDocs = ((int) ((1+groupCounter)*docsPerGroupBlock)) - ((int) (groupCounter*docsPerGroupBlock));
            }
            groupBlockField.setValue(groupBlocks[groupCounter]);
            for(int docCount=0;docCount<numDocs;docCount++) {
              final Document doc = docs.nextDoc(docState);
              if (doc == null) {
                break;
              }
              final int id = LineFileDocs.idToInt(idField.stringValue());
              if (id >= numTotalDocs) {
                break;
              }
              if (((1+id) % 1000000) == 0) {
                System.out.println("Indexer: " + (1+id) + " docs... (" + (System.currentTimeMillis() - tStart) + " msec)");
              }
              group100Field.setValue(group100[id%100]);
              group10KField.setValue(group10K[id%10000]);
              group100KField.setValue(group100K[id%100000]);
              group1MField.setValue(group1M[id%1000000]);
              docsGroup.add(cloneDoc(doc));
            }
            final int docCount = docsGroup.size();
            docsGroup.get(docCount-1).add(groupEndField);
            //System.out.println("nd=" + docCount);
            if (docCount > 0) {
              w.addDocuments(docsGroup);
              count.addAndGet(docCount);
              docsGroup.clear();
            } else {
              break;
            }
          }
        } else {

          while(true) {
            final Document doc = docs.nextDoc(docState);
            if (doc == null) {
              break;
            }
            final int id = LineFileDocs.idToInt(idField.stringValue());
            if (numTotalDocs != -1 && id >= numTotalDocs) {
              break;
            }
            if (((1+id) % 1000000) == 0) {
              System.out.println("Indexer: " + (1+id) + " docs... (" + (System.currentTimeMillis() - tStart) + " msec)");
            }
            if (doUpdate) {
              delTerm = new Term("id", idField.stringValue());
            }
            w.updateDocument(delTerm, doc);
            count.incrementAndGet();
          }
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
