
package controllers

import java.io.PrintWriter
import java.util.Properties

import com.brkyvz.spark.linalg._
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.{SearchHit, SearchIterator}
import edu.stanford.nlp.ling.CoreAnnotations.{SentencesAnnotation, _}
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.util.CoreMap
import org.apache.spark.mllib.linalg
import org.apache.spark.mllib.linalg.{Vectors, _}
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.Source


object produceCoocMatrix_new {

  ////////////////////
  // get a Map with all named entities and their transformations (e.g. coca cola -> coca_cola OR 07.05.1987 -> daystreamDate)
  ////////////////////

  def namedEntitiesTransformation(NerNorms: List[String], NerTypes: List[String]): scala.collection.mutable.Map[String, String] = {

    val NamedEntitiesTransformationsOutput = scala.collection.mutable.Map[String, String]()

    new PrintWriter("allNamedEntitiesTransformations_small.txt") {
      //var allNamedEntitiesTransformations = scala.collection.mutable.Map[String, String]()
      if (NerNorms.lengthCompare(NerTypes.size) == 0) {
        NerNorms.zip(NerTypes).zipWithIndex.map(x => (x._1._1, x._1._2, x._2)).foreach(triple => { // for each element in the iterator (NerNorm, NerType, index)

          if (triple._2.equals("date")) {
            NamedEntitiesTransformationsOutput(triple._1) = "daystreamDate"
          } else if (triple._2.equals("distance")) {
            NamedEntitiesTransformationsOutput(triple._1) = "daystreamDistance"
          } else if (triple._2.equals("duration")) {
            NamedEntitiesTransformationsOutput(triple._1) = "daystreamDuration"
          } else if (triple._2.equals("money")) {
            NamedEntitiesTransformationsOutput(triple._1) = "daystreamMoney"
          } else if (triple._2.equals("number")) {
            NamedEntitiesTransformationsOutput(triple._1) = "daystreamNumber"
          } else if (triple._2.equals("percent")) {
            NamedEntitiesTransformationsOutput(triple._1) = "daystreamPercent"
          } else if (triple._2.equals("time")) {
            NamedEntitiesTransformationsOutput(triple._1) = "daystreamTime"
          } else if (triple._2.equals("url")) {
            //allNamedEntitiesTransformationsOutput(NerNormsResults(i)) = "daystreamUrl"
          } else {
            NamedEntitiesTransformationsOutput(triple._1) = triple._1.replaceAll(" ", "_")
          }

        })
      } else {
        println("MISMATCH: NerNorms.size = " + NerNorms.size + " & NerTypes.size = " + NerTypes.size)
      }
      //allNamedEntitiesTransformationsOutput.foreach(x => write(x + "\n")) // write distinct list du file
      close() // close file
    }
    NamedEntitiesTransformationsOutput
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load stanford parser
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  val props: Properties = new Properties() // set properties for annotator
  props.put("annotators", "tokenize, ssplit,pos") // set properties
  val pipeline: StanfordCoreNLP = new StanfordCoreNLP(props) // annotate file
  // input: one word / output: pos Tag of that word
  def getPOS(sentence: String): String = { // get POS tags per sentence
    val document: Annotation = new Annotation(sentence)
    pipeline.annotate(document) // annotate
    val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
    val back = for {
      sentence: CoreMap <- sentences
      token: CoreLabel <- sentence.get(classOf[TokensAnnotation]).asScala.toList
      pos: String = token.get(classOf[PartOfSpeechAnnotation])

    } yield pos // return List of POS tags
    back.mkString("")
  }

  ////////////////////
  // get co-occurrences (get the cos between the wordvectors) and save them to a file
  ////////////////////

  def allCoOccurrences(numberOfResults: Int = 10, minimumCoocsFilter: Int) {
    implicit val timeout: FiniteDuration = Duration(1000, "seconds") // is the timeout for the SearchIterator.hits method
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    val windowWidth: Int = 9

    val wordListRows = Vector.newBuilder[String] // Word which occur next to the word in the middle of the window
    var Indices = scala.collection.immutable.Vector[scala.collection.immutable.Vector[Int]]() // words which are in the middle of the window with the indices of the wordlist
    var Values = scala.collection.immutable.Vector[scala.collection.immutable.Vector[Double]]() // count of occurences corresponding to the indices
    var Token = scala.collection.immutable.Vector[scala.collection.immutable.Vector[String]]() // count of occurences corresponding to the indices

    val wordListRows2 = Vector.newBuilder[String] // Word which occur next to the word in the middle of the window
    var Values2 = scala.Vector.newBuilder[scala.collection.immutable.Vector[Double]]
    val Token2 = scala.Vector.newBuilder[scala.Vector[String]]



    var counter: Int = 0
    var countWords = 0

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // this function takes sentences from elastic, transform them and feed the cooc matrix
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    def cooc(iterator: Iterator[SearchHit]): Unit = {

      var iteratorCounter = 0
      iterator.foreach(searchhit => { // for each element in the iterator
        if (iteratorCounter >= numberOfResults && numberOfResults != 0) { // make it possible to retain only a fixed number of coocs
          return
        }
        iteratorCounter += 1
        println("searchhit counter: " + counter)
        println("wordListRows.result().size: " + wordListRows.result().size)
        //println(Token)
        counter += 1

        // - - - - - - - - - - - - - - - - - - - - - - - - -
        // filter words that we don't line (internet stuff mostly)
        // - - - - - - - - - - - - - - - - - - - - - - - - -

        val cleaned0 = searchhit.sourceField("posLemmas").toString.split(" ~ ").toList

          .filter(x => !x.matches("http" + ".*")
            && !x.matches("<a" + ".*")
            && !x.matches("www" + ".*")
            && !x.matches(".*" + ".com" + ".*")
            && !x.matches(".*" + ".org" + ".*")
            && !x.matches(".*" + ".net" + ".*")
            && !x.matches("<img" + ".*")
            && !x.matches("http" + ".*")
            && !x.matches("-lsb-")
            && !x.matches("-rrb-"))
          .map(x => x.toLowerCase) // filter result (www, http, <a)

        // - - - - - - - - - - - - - - - - - - - - - - - - -
        // lowercase NER Norms and NER types & get a List with replacements for later use
        // - - - - - - - - - - - - - - - - - - - - - - - - -

        val NerNorms: List[String] = searchhit.sourceField("nerNorm").toString.split(" ~ ").toList.map(x => x.toLowerCase)
        val NerTypes: List[String] = searchhit.sourceField("nerTyp").toString.split(" ~ ").toList.map(x => x.toLowerCase)
        val namedEntitiesTransformations: mutable.Map[String, String] = namedEntitiesTransformation(NerNorms, NerTypes)

        // - - - - - - - - - - - - - - - - - - - - - - - - -
        // replace with NER tags
        // - - - - - - - - - - - - - - - - - - - - - - - - -

        val cleaned1 = namedEntitiesTransformations.foldLeft(cleaned0.mkString(" "))((a, b) => a.replaceAllLiterally(" " + b._1 + " ", " " + b._2 + " ")).split(" ").toList

        // - - - - - - - - - - - - - - - - - - - - - - - - -
        // ssplit
        // - - - - - - - - - - - - - - - - - - - - - - - - -

        def ssplit(text: String): Seq[String] = {
          //val text = Source.fromFile(filename).getLines.mkString
          val props: Properties = new Properties()
          props.put("annotators", "tokenize, ssplit")
          val pipeline: StanfordCoreNLP = new StanfordCoreNLP(props)
          val document: Annotation = new Annotation(text)
          // run all Annotator - Tokenizer on this text
          pipeline.annotate(document)
          val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
          (for {
            sentence: CoreMap <- sentences
          } yield sentence).map(_.toString)
        }


        val cleaned2: List[List[String]] = ssplit(cleaned1.mkString(" ")).map(x => x.split(" ").toList.filter(!_.equals("")).filter(!_.contains(","))).toList // ssplit

        cleaned2.foreach(x => x.foreach(_ => countWords += 1))

        // - - - - - - - - - - - - - - - - - - - - - - - - -
        // calc coocs
        // - - - - - - - - - - - - - - - - - - - - - - - - -


        cleaned2.foreach(sentence => {

          val appending = (0 to windowWidth / 2).map(_ => "imunimportant").toList // important because we have to enlarge the sentence so that the sliding window starts at the forst word
          val enlargedSentence = appending ::: sentence ::: appending


          enlargedSentence
            .sliding(windowWidth) // create sliding windows
            .foreach(window => {
            // for each window
            val centerElement: String = window(windowWidth / 2) // get the middle element in the window
            val wordInWindowPOS = getPOS(centerElement)
            if (wordInWindowPOS == "JJ" || wordInWindowPOS == "JJR" || wordInWindowPOS == "JJS" || wordInWindowPOS == "NN" || wordInWindowPOS == "NNS" || wordInWindowPOS == "NNP" || wordInWindowPOS == "NNPS" || wordInWindowPOS == "PDT" || wordInWindowPOS == "RB" || wordInWindowPOS == "RBR" || wordInWindowPOS == "RBS" || wordInWindowPOS == "RP" || wordInWindowPOS == "VB" || wordInWindowPOS == "VBD" || wordInWindowPOS == "VBG" || wordInWindowPOS == "VBN" || wordInWindowPOS == "VBP" || wordInWindowPOS == "VBZ" || wordInWindowPOS == "VBG") {


              val indexOfCenterElement = wordListRows.result().indexOf(centerElement) // is the current middle element present in the coOccurrences matrix
              if (indexOfCenterElement >= 0) { // the element is present in rows
                window.foreach(wordInWindow => { // for every word y in the window

                  // gibt es das Wort in der globalen Wortliste?
                  // val indexOfwordInWindow = wordListRows.result().indexOf(wordInWindow)
                  // gibt es das Wort in der lokalen Wortliste?

                  val wordInWindowPOS = getPOS(centerElement)
                  if (wordInWindowPOS == "JJ" || wordInWindowPOS == "JJR" || wordInWindowPOS == "JJS" || wordInWindowPOS == "NN" || wordInWindowPOS == "NNS" || wordInWindowPOS == "NNP" || wordInWindowPOS == "NNPS" || wordInWindowPOS == "PDT" || wordInWindowPOS == "RB" || wordInWindowPOS == "RBR" || wordInWindowPOS == "RBS" || wordInWindowPOS == "RP" || wordInWindowPOS == "VB" || wordInWindowPOS == "VBD" || wordInWindowPOS == "VBG" || wordInWindowPOS == "VBN" || wordInWindowPOS == "VBP" || wordInWindowPOS == "VBZ" || wordInWindowPOS == "VBG") {

                    val indexOfwordInWindow = Token(indexOfCenterElement).indexOf(wordInWindow)
                    if (indexOfwordInWindow >= 0) {
                      if (centerElement != wordInWindow && centerElement != "imunimportant" && wordInWindow != "imunimportant" && centerElement != "," && wordInWindow != ",") {
                        Values = Values.updated(indexOfCenterElement, Values(indexOfCenterElement).updated(indexOfwordInWindow, Values(indexOfCenterElement)(indexOfwordInWindow) + 1)) // assign updated cooccurence count
                      }
                    } else {
                      Token = Token.updated(indexOfCenterElement, Token(indexOfCenterElement) :+ wordInWindow)
                      Values = Values.updated(indexOfCenterElement, Values(indexOfCenterElement) :+ 0.0)

                      var indexOfwordInWindow = Token(indexOfCenterElement).size - 1 // is the current word in the window present in the wordListColumns?
                      if (indexOfwordInWindow >= 0) {
                        if (centerElement != wordInWindow && centerElement != "imunimportant" && wordInWindow != "imunimportant" && centerElement != "," && wordInWindow != ",") {
                          Values = Values.updated(indexOfCenterElement, Values(indexOfCenterElement).updated(indexOfwordInWindow, Values(indexOfCenterElement)(indexOfwordInWindow) + 1)) // assign updated cooccurence count
                        }
                      } else {
                        println("Something bad happend 1")
                      }
                    }
                  }
                })
              } else if (indexOfCenterElement < 0) {


                wordListRows += centerElement
                Token = Token :+ Vector[String]()
                Values = Values :+ Vector[Double]()
                val indexOfCenterElement = wordListRows.result().size - 1 // is the current middle element present in the coOccurrences matrix
                if (indexOfCenterElement >= 0) { // the element is present in rows
                  window.foreach(wordInWindow => { // for every word y in the window
                    // gibt es das Wort in der globalen Wortliste?
                    // val indexOfwordInWindow = wordListRows.result().indexOf(wordInWindow)
                    // gibt es das Wort in der lokalen Wortliste?
                    val wordInWindowPOS = getPOS(centerElement)
                    if (wordInWindowPOS == "JJ" || wordInWindowPOS == "JJR" || wordInWindowPOS == "JJS" || wordInWindowPOS == "NN" || wordInWindowPOS == "NNS" || wordInWindowPOS == "NNP" || wordInWindowPOS == "NNPS" || wordInWindowPOS == "PDT" || wordInWindowPOS == "RB" || wordInWindowPOS == "RBR" || wordInWindowPOS == "RBS" || wordInWindowPOS == "RP" || wordInWindowPOS == "VB" || wordInWindowPOS == "VBD" || wordInWindowPOS == "VBG" || wordInWindowPOS == "VBN" || wordInWindowPOS == "VBP" || wordInWindowPOS == "VBZ" || wordInWindowPOS == "VBG") {

                      val indexOfwordInWindow = Token(indexOfCenterElement).indexOf(wordInWindow)
                      if (indexOfwordInWindow >= 0) {
                        if (centerElement != wordInWindow && centerElement != "imunimportant" && wordInWindow != "imunimportant" && centerElement != "," && wordInWindow != ",") {
                          Values = Values.updated(indexOfCenterElement, Values(indexOfCenterElement).updated(indexOfwordInWindow, Values(indexOfCenterElement)(indexOfwordInWindow) + 1)) // assign updated cooccurence count
                        }
                      } else {
                        Token = Token.updated(indexOfCenterElement, Token(indexOfCenterElement) :+ wordInWindow)
                        Values = Values.updated(indexOfCenterElement, Values(indexOfCenterElement) :+ 0.0)

                        var indexOfwordInWindow = Token(indexOfCenterElement).size - 1 // is the current word in the window present in the wordListColumns?
                        if (indexOfwordInWindow >= 0) {
                          if (centerElement != wordInWindow && centerElement != "imunimportant" && wordInWindow != "imunimportant" && centerElement != "," && wordInWindow != ",") {
                            Values = Values.updated(indexOfCenterElement, Values(indexOfCenterElement).updated(indexOfwordInWindow, Values(indexOfCenterElement)(indexOfwordInWindow) + 1)) // assign updated cooccurence count
                          }
                        } else {
                          println("Something bad happend 2")
                        }
                      }
                    }

                  })
                }
              } else {
                println("Something bad happend 3")
              }
            }
          })
        }
        )
      })
    }



    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // execute the method from above which creates the cooc matrix
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    //cooc(iterator = SearchIterator.hits(client, search("test") matchAllQuery() keepAlive (keepAlive = "10m") size 100 sourceInclude List("nerNorm", "nerTyp", "posLemmas"))) // returns 100 values and blocks until the iterator gets to the last element
    cooc(SearchIterator.hits(client, search("test") query matchQuery("posLemmas", "company takeover buyer Sale email Advisers offer asset potential money energy buy economy economic market") keepAlive (keepAlive = "10m") size (100) sourceInclude (List("nerNorm", "nerTyp", "posLemmas")))) // returns 50 values and blocks until the iterator gets to the last element
    client.close()


    println("")
    println("number of words which have been processed: " + countWords)
    println("Indices.size: " + Indices.size)
    println("Token.size: " + Token.size)
    println("Values.size: " + Values.size)
    println("wordListRows.size: " + wordListRows.result().size)




    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // filter the coOccurrences, they have to have a least 50 different cooccurence words AND order
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    /*val cleanedOrderedCoOccurrences: ListMap[String, mutable.Map[String, Int]] = ListMap(coOccurrences.retain((_, v) => v.size > atleastCooccurence).toVector.sortBy {
      _._1
    }: _*)
    println("size(cleanedOrderedCoOccurrences): " + cleanedOrderedCoOccurrences.size)*/


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // filter rows which have less than ... coocs
    // - - - - - - - - - - - - - - - - - - - - - - - - -
    println("filter rows which have less than ... coocs")
    println("")



    for(i<-0 until Token.size){
      if(Token(i).size>minimumCoocsFilter){
        Values2 += Values(i)
        wordListRows2 += wordListRows.result()(i)
        Token2 += Token(i)
      }
    }




    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // build wordListCols to get a global list for the col words
    // - - - - - - - - - - - - - - - - - - - - - - - - -
    println("build wordListCols")
    println("")

    val wordListCols = Vector.newBuilder[String] // Word which occur next to the word in the middle of the window

    for (tokenRow <- Token2.result()) {
      val tmpIndiceRow = Vector.newBuilder[Int]
      for (token <- tokenRow) {
        wordListCols += token
      }
    }

    val wordListColsasSet = wordListCols.result().toSet.toVector

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // build Indices Vector
    // - - - - - - - - - - - - - - - - - - - - - - - - -
    println("build Indices Vector")
    println("")


    for (tokenRow <- Token2.result()) {
      val tmpIndiceRow = Vector.newBuilder[Int]
      for (token <- tokenRow) {
        val index: Int =  wordListColsasSet.indexOf(token)
        tmpIndiceRow += index
      }
      Indices = Indices :+ tmpIndiceRow.result()
    }

    println("")
    println("Values2.size: " + Values2.result().size)
    println("wordListRows2.size: " + wordListRows2.result().size)
    println("wordListCols.size: " + wordListCols.result().size)
    println("wordListColsAsSet.size: " + wordListColsasSet.size)
    println("Indices.size: " + Indices.size)
    println("")







    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // save indices, values and words in files
    // - - - - - - - - - - - - - - - - - - - - - - - - -


    /*new PrintWriter("data/indices.txt") { // open new file
      for (i <- 0 until Indices.size) {
        for (j <- 0 until Indices(i).size) {
          write(Indices(i)(j) + ",")
        }
        write("\n")
      }
      close // close file
    }
    new PrintWriter("data/values.txt") { // open new file
      for (i <- 0 until Values.size) {
        for (j <- 0 until Values(i).size) {
          write(Values(i)(j) + ",")
        }
        write("\n")

      }
      close // close file
    }*/
    new PrintWriter("data/wordListRows.txt") { // open new file
      for (i <- 0 until wordListRows2.result().size) {
        write(wordListRows2.result()(i) + ",")
      }
      close // close file
    }
    /*
        val data = Array.newBuilder[linalg.Vector]
        for (i<-0 until IndicesCleaned.result().size){
          data += Vectors.sparse(wordListRows.result().size,IndicesCleaned.result()(i).toArray,ValuesCleaned.result()(i).toArray)
        }

        println("data: "+data.result()(0).toDense)

        new PrintWriter("data/sparseMatrix.txt") { // open new file
          for (i<- data.result()){
            i.toDense.toArray.foreach(x=>write(x.toString+" "))
            write("\n")
          }
          close // close file
        }*/

    new PrintWriter("data/row.txt") { // open new file
      for (i <- 0 until Indices.size) {
        Indices(i).foreach(x => write(i + " "))
      }
      close // close file
    }

    new PrintWriter("data/col.txt") { // open new file
      for (i <- Indices) {
        i.foreach(x => write(x.toString + " "))
      }
      close // close file
    }


    new PrintWriter("data/data.txt") { // open new file
      for (i <- Values2.result()) {
        i.foreach(x => write(x.toString + " "))
      }
      close // close file
    }

    new PrintWriter("data/info.txt") { // open new file
      write("minimumCoocsFilter: " + minimumCoocsFilter)
      write("\n")
      write("#docs: "+numberOfResults)
      write("\n")
      write("number of words which have been processed: " + countWords)
      write("\n")
      write("\n")

      write("MUSS IN PYTHON SVM EINGETRAGEN WERDEN:")
      write("\n")
      write("rows: " + wordListRows2.result().size)
      write("\n")
      write("cols: " + wordListColsasSet.size)

      close // close file
    }




    // - - - - - - - - - - - - - - - - - - - - - - - - -


    //allDistances(coOccurrencesCleaned)
  }


  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // great but slow as f***
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def calcPC(): Unit = {
    val wordListRows = scala.Vector.newBuilder[String]
    val wordListCols = scala.Vector.newBuilder[String]
    var IndicesCleaned = scala.Vector.newBuilder[scala.Vector[Int]]
    var ValuesCleaned = scala.Vector.newBuilder[scala.collection.immutable.Vector[Double]]

    val one = Source.fromFile("data/wordListRows.txt").getLines.toVector
    val two = Source.fromFile("data/wordListCols.txt").getLines.toVector
    val three = Source.fromFile("data/indices.txt").getLines.toVector
    val four = Source.fromFile("data/values.txt").getLines.toVector


    val size = 2
    for (i <- 0 until 1) {
      one(i).split(",").foreach(x => wordListRows += x)
    }
    for (i <- 0 until 1) {
      two(i).split(",").foreach(x => wordListCols += x)
    }
    for (i <- 0 until size) {

      IndicesCleaned += (three(i).split(",").map(_.toInt).toVector)
    }
    for (i <- 0 until size) {
      ValuesCleaned += (four(i).split(",").map(_.toDouble).toVector)
    }

    val wordListRowsResult = wordListRows.result()
    val wordListColsResult = wordListCols.result()
    var IndicesCleanedResult = IndicesCleaned.result()
    var ValuesCleanedResult = ValuesCleaned.result()



    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // calc matrix in spark principal components in spark
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    //Create a SparkContext to initialize Spark
    val conf = new SparkConf()
    conf.set("spark.driver.memory", "4")

    conf.setMaster("local[*]")
      .setAppName("Word Count")

    val sc = new SparkContext(conf)


    val data = Array.newBuilder[linalg.Vector]
    for (i <- 0 until IndicesCleaned.result().size) {
      data += Vectors.sparse(wordListCols.result().size, IndicesCleaned.result()(i).toArray, ValuesCleaned.result()(i).toArray)
    }

    val rows: RDD[linalg.Vector] = sc.parallelize(data.result())
    val mat: RowMatrix = new RowMatrix(rows)
    // Compute the top 4 principal components.
    // Principal components are stored in a local dense matrix.
    val pc = mat.computePrincipalComponents(5)
    // Project the rows to the linear space spanned by the top 4 principal components.
    val projected: RowMatrix = mat.multiply(pc)
    val collect: Array[Vector] = projected.rows.collect()
    //println("U factor is:")
    //collect.foreach { vector => println(vector) }

    new PrintWriter("data/principalComponents.txt") { // open new file
      for (i <- 0 until collect.size) {
        write(collect(i).toDense.toArray.mkString(","))
        write("\n")
      }
      close // close file
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // just tests
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    //cosOfAngleFirstWordSecondWord = (dotProductFirstWordSecondWord / (lengthFirstWordVector * math.pow(lengthSecondWordVector, 0.7))
    println("data.result()(1): " + data.result()(1).toDense)
    println("data.result()(2): " + data.result()(2).toDense)


    val e = new DenseMatrix(1, 300, collect(1).toDense.toArray)
    val r = new DenseMatrix(1, 300, collect(4).toDense.toArray)
    val x = ((e * r.transpose).compute())
    val xx = (math.sqrt((e * e.transpose).compute().toString.toDouble))
    val xxx = (math.sqrt((r * r.transpose).compute().toString.toDouble))
    println("e*r.transpose: " + (x / (xx * xxx)).compute())
    val t = new DenseMatrix(1, data.result()(1).toDense.size, data.result()(1).toDense.toArray)
    val z = new DenseMatrix(1, data.result()(4).toDense.size, data.result()(4).toDense.toArray)
    val y = ((t * z.transpose).compute())
    val yy = (math.sqrt((t * t.transpose).compute().toString.toDouble))
    val yyy = (math.sqrt((z * z.transpose).compute().toString.toDouble))
    println("t*z.transpose: " + (y / (yy * yyy)).compute())


    println("ready with CoOccurrences")
    sc.stop()
  }


  def calcPCOnTestStrings(): Unit = {

    val sparseMatrixTest = scala.Vector.newBuilder[scala.collection.immutable.Vector[Double]]
    val one = Source.fromFile("data/sparseMatrixTest.txt").getLines.toVector
    for (i <- 0 until 10) {
      sparseMatrixTest += (one(i).split(" ").map(_.toDouble).toVector)
    }
    val lsa = scala.Vector.newBuilder[scala.collection.immutable.Vector[Double]]
    val two = Source.fromFile("data/lsa.txt").getLines.toVector
    for (i <- 0 until 10) {
      lsa += (two(i).split(" ").map(_.toDouble).toVector)
    }

    val e = new DenseMatrix(1, sparseMatrixTest.result()(1).size, sparseMatrixTest.result()(1).toArray)
    val r = new DenseMatrix(1, sparseMatrixTest.result()(2).size, sparseMatrixTest.result()(2).toArray)
    val x = ((e * r.transpose).compute())
    val xx = (math.sqrt((e * e.transpose).compute().toString.toDouble))
    val xxx = (math.sqrt((r * r.transpose).compute().toString.toDouble))
    println("e*r.transpose: " + (x / (xx * xxx)).compute())


    val t = new DenseMatrix(1, lsa.result()(1).size, lsa.result()(1).toArray)
    val z = new DenseMatrix(1, lsa.result()(2).size, lsa.result()(2).toArray)
    val y = ((t * z.transpose).compute())
    val yy = (math.sqrt((t * t.transpose).compute().toString.toDouble))
    val yyy = (math.sqrt((z * z.transpose).compute().toString.toDouble))
    println("t*z.transpose: " + (y / (yy * yyy)).compute())

  }

  def main(args: Array[String]): Unit = {

    allCoOccurrences(100000, 50) // 1. to get a cooc matrix
    //calcPCOnTestStrings() // 2. calc pca on it : but very slow, better use python (in folder dimReduction)

  }
}

