
package controllers

import java.io.{File, IOException, PrintWriter}
import java.util.Properties

import com.sksamuel.avro4s.AvroOutputStream
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.{SearchHit, SearchIterator}
import edu.stanford.nlp.ling.CoreAnnotations.{SentencesAnnotation, _}
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.util.CoreMap
import org.apache.avro.file.DataFileReader
import org.apache.avro.specific.SpecificDatumReader

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.concurrent.duration._


class analogyExtr_avroVersion(coOccurrences: Map[String, Map[String, Int]], pipelineNER: StanfordCoreNLP, pipelineSplit: StanfordCoreNLP, var vectorDoc1: Map[String, Int], var lengthFirstWordVector: Double) {



  ////////////////////
  // get a file with all tokens (cleaned)
  ////////////////////

  def allTokens() {
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    implicit val timeout: FiniteDuration = Duration(10, "seconds") // is the timeout for the SearchIterator.hits method
    val listBuilder = List.newBuilder[String]

    val iterator = SearchIterator.hits(client, search("test").matchAllQuery.keepAlive(keepAlive = "1m").size(100).sourceInclude("posLemmas")) // returns 50 values and blocks until the iterator gets to the last element
    iterator.foreach(x => { // for each element in the iterator
      x.sourceField("posLemmas").toString.split(" ~ ").toList.distinct
        .filter(x => !x.matches("http" + ".*")
          && !x.matches("<a" + ".*")
          && !x.matches("www" + ".*")
          && !x.matches(".*" + ".com" + ".*")
          && !x.matches(".*" + ".org" + ".*")
          && !x.matches(".*" + ".net" + ".*")
          && !x.matches("<img" + ".*"))
        .map(x => x.toLowerCase).foreach(listBuilder += _) // filter result (www, http, <a)
    })

    new PrintWriter("allTokens.txt") { // open new file
      listBuilder.result().distinct.sorted.foreach(x => write(x + "\n")) // write distinct list du file
      close() // close file
    }
    client.close() // close HttpClient
  }

  ////////////////////
  // get a file with all named entities (cleaned)
  ////////////////////

  def allNamedEntities() {
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    implicit val timeout: FiniteDuration = Duration(10, "seconds") // is the timeout for the SearchIterator.hits method
    val listBuilder = List.newBuilder[String]

    val iterator = SearchIterator.hits(client, search("test").matchAllQuery.keepAlive(keepAlive = "1m").size(50).sourceInclude("nerNorm")) // returns 50 values and blocks until the iterator gets to the last element
    iterator.foreach(x => { // for each element in the iterator
      x.sourceField("nerNorm").toString.split(" ~ ").toList.distinct

        .map(x => x.toLowerCase).foreach(listBuilder += _) // filter result (www, http, <a)
    })

    new PrintWriter("allNamedEntities.txt") { // open new file
      listBuilder.result().distinct.sorted.foreach(x => write(x + "\n")) // write distinct list du file
      close() // close file
    }
    client.close() // close HttpClient
  }

  ////////////////////
  // get a file with all named entity types (cleaned)
  ////////////////////

  def allNerTyps() {
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    implicit val timeout: FiniteDuration = Duration(10, "seconds") // is the timeout for the SearchIterator.hits method
    val listBuilder = List.newBuilder[String]

    val iterator = SearchIterator.hits(client, search("test").matchAllQuery.keepAlive(keepAlive = "1m").size(50).sourceInclude("nerTyp")) // returns 50 values and blocks until the iterator gets to the last element
    iterator.foreach(x => { // for each element in the iterator
      x.sourceField("nerTyp").toString.split(" ~ ").toList.distinct

        .map(x => x.toLowerCase).foreach(listBuilder += _) // filter result (www, http, <a)
    })

    new PrintWriter("allNerTyps.txt") { // open new file
      listBuilder.result().distinct.sorted.foreach(x => write(x + "\n")) // write distinct list du file
      close() // close file
    }
    client.close() // close HttpClient
  }


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

  ////////////////////
  // get co-occurrences (cleaned: filter url's and replace entities with their transformation)
  ////////////////////


  def allCoOccurrences(atleastCooccurence: Int = 0,numberOfResults: Int = 0)(implicit client: HttpClient) {
    implicit val timeout: FiniteDuration = Duration(1000, "seconds") // is the timeout for the SearchIterator.hits method
    val windowWidth: Int = 9
    val coOccurrences = scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, Int]]()
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

        def ssplit(text:String): Seq[String] = {
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


        val cleaned2: List[List[String]] = ssplit(cleaned1.mkString(" ")).map(x => x.split(" ").toList.filter(!_.equals(""))).toList // ssplit

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
            val centerElement = window(windowWidth / 2) // get the middle element in the window
            coOccurrences.get(centerElement) match { // is the current middle element present in the coOccurrences matrix
              case Some(centerElementWordMap) => // if yes // centerElementWordMap = list of words in the Map of the current middleWord
                window.foreach(wordInWindow => { // for every word y in the window
                  centerElementWordMap.get(wordInWindow) match { // test if word(key) is already in the map
                    case Some(wordInWindowValue) => // if the word is in the map
                      if (centerElement != wordInWindow && centerElement != "imunimportant" && wordInWindow != "imunimportant" && centerElement != "," && wordInWindow != ",") {
                        coOccurrences(centerElement)(centerElement) = wordInWindowValue + 1 // assign updated cooccurence count
                      }
                    case None => {
                    }
                      if (centerElement != wordInWindow && centerElement != "imunimportant" && wordInWindow != "imunimportant" && centerElement != "," && wordInWindow != ",") {
                        coOccurrences(centerElement)(wordInWindow) = 1
                      }
                  }
                })
              case None => // word not in Matrix

                coOccurrences(centerElement) = collection.mutable.Map[String, Int]()
                window.foreach(wordInWindow => { // for every word y in the window
                  if (centerElement != wordInWindow && centerElement != "imunimportant" && wordInWindow != "imunimportant" && centerElement != "," && wordInWindow != ",") {
                    coOccurrences(centerElement)(wordInWindow) = 1 // set wordcount the centerElement
                  }
                })
            }
          })
        }
        )
      })
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // feed the cooc matrix with results from elastic
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    cooc(iterator = SearchIterator.hits(client, search("test") matchAllQuery() keepAlive (keepAlive = "10m") size 100 sourceInclude List("nerNorm", "nerTyp", "posLemmas"))) // returns 100 values and blocks until the iterator gets to the last element
    //cooc(numberOfResults = 100, SearchIterator.hits(client, search("test") query matchQuery("posLemmas", "personality New York work stock market") keepAlive (keepAlive = "10m") size (100) sourceInclude (List("nerNorm", "nerTyp", "posLemmas")))) // returns 50 values and blocks until the iterator gets to the last element
    //cooc(numberOfResults = 1000, SearchIterator.hits(client, search("test") query matchQuery("posLemmas", "important") keepAlive (keepAlive = "10m") size (100) sourceInclude (List("nerNorm", "nerTyp", "posLemmas")))) // returns 50 values and blocks until the iterator gets to the last element

    println("countWords: " + countWords)
    println("size(coOccurrences): " + coOccurrences.size)


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // filter the coOccurrences, they have to have a least 50 different cooccurence words AND order
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    /*val cleanedOrderedCoOccurrences: ListMap[String, mutable.Map[String, Int]] = ListMap(coOccurrences.retain((_, v) => v.size > atleastCooccurence).toVector.sortBy {
      _._1
    }: _*)
    println("size(cleanedOrderedCoOccurrences): " + cleanedOrderedCoOccurrences.size)*/

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

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // filter occurrences, only keep words with these POS: JJ JJR JJS NN NNS NNP NNPS PDT RB RBR RBS RP VB VBD VBG VBN VBP VBZ VBG
    // cooccurences are not affected
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    val cleanedOrderedCoOccurrences2: mutable.Map[String, Map[String, Int]] = coOccurrences.retain((_, v) => v.size > atleastCooccurence).filter(x => {
      getPOS(x._1) == "JJ" || getPOS(x._1) == "JJR" || getPOS(x._1) == "JJS" || getPOS(x._1) == "NN" || getPOS(x._1) == "NNS" || getPOS(x._1) == "NNP" || getPOS(x._1) == "NNPS" || getPOS(x._1) == "PDT" || getPOS(x._1) == "RB" || getPOS(x._1) == "RBR" || getPOS(x._1) == "RBS" || getPOS(x._1) == "RP" || getPOS(x._1) == "VB" || getPOS(x._1) == "VBD" || getPOS(x._1) == "VBG" || getPOS(x._1) == "VBN" || getPOS(x._1) == "VBP" || getPOS(x._1) == "VBZ" || getPOS(x._1) == "VBG"
    }).map(x => (x._1, x._2.toMap))
    println("size(cleanedOrderedCoOccurrences2): " + cleanedOrderedCoOccurrences2.size)

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // write it txt
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    /* dont need it because we have an avro
    new PrintWriter("coOccurrences.txt") { // open new file
      cleanedOrderedCoOccurrences2.foreach(x => write(x + "\n"))
      close // close file
    }*/

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // write it to an avro
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    case class wordListCaseClass(word: String, cooc: Map[String, Int])
    val os = AvroOutputStream.data[wordListCaseClass](new File("coOccurrences.avro"))
    cleanedOrderedCoOccurrences2.foreach(x => {
      os.write(Seq(wordListCaseClass(x._1, x._2)))
    })
    os.flush()
    os.close()


    println("ready with CoOccurrences")
    //allDistances(cleanedOrderedCoOccurrences2)
  }

  ////////////////////
  // get co-occurrences (get the cos between the wordvectors) and save them to a file
  ////////////////////

  def allDistances() {

    val borderForCosAngle: Double = 0.0
    //val wordOfInterest = "important"

    val coOccurrences = readAvro()
    val coOccurrenceSize = coOccurrences.size
    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //  Calculate SVD // interrupted in favour of a better idea
    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    /*
    def getSVD(coOccurrencesNotSparce: Map[String, Map[String, Int]], dimensions: Int): Map[String, List[Int]] ={
      var words:scala.collection.mutable.IndexedSeq[String] = scala.collection.mutable.IndexedSeq[String]()
      var coOccurrencesSparce: scala.collection.mutable.Map[String, List[Int]] = scala.collection.mutable.Map[String, List[Int]]()
      coOccurrencesNotSparce.foreach(entryIncoOccurrencesNotSparce=>

        coOccurrencesSparce += entryIncoOccurrencesNotSparce._1
      )
    }*/

    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //  This is a try to deserialize it with avro4s but we dont need it because we have the java api (above)
    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    /*
    case class coocMap(map: Map[String, Int])
    //implicit val fromRecord = FromRecord[coocMap]
    val schema = AvroSchema[coocMap]
    val is = AvroInputStream.data[coocMap](new File("coOccurrences.avro"))
    val pizzas = is.iterator.toSet
    is.close()
    */

    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //  calculate relative cosine angle martix and write it in a file
    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    var coOccurrencesCounter = 0
    new PrintWriter("cosOfAngleMatrix.txt") { // get new PrintWriter

      // coOccurrences e.g.: (administrative,Map(pay -> 1, role -> 1, disregard -> 1,

      coOccurrences /*filter(x=>x._1==wordOfInterest)*/ .foreach { case (firstWord, firstMap) => // for each word in the map
        System.out.println("calculating " + coOccurrencesCounter + " of " + coOccurrenceSize)
        coOccurrencesCounter += 1
        val cosOfAngleMatrix = scala.collection.mutable.Map[String, ListMap[String, Double]]() // we can save the distances to other word vectors here
        cosOfAngleMatrix(firstWord) = ListMap[String, Double]() // make a entry for the current word
      val lengthFirstWordVector = math.floor(scala.math.sqrt(firstMap.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2))) * 100) / 100 // calc the length for the current word vector
        coOccurrences.foreach { case (secondWord, secondMap) => // get the seconds word for comparison
          var dotProductFirstWordSecondWord: Int = 0 // initiate the dotproduct
          secondMap.foreach { case (wordInSecondMap, countInSecondMap) => // get every word in the second word
            firstMap.get(wordInSecondMap) match { // and look if this words are present in the first word
              case Some(countInFirstMap) =>
                dotProductFirstWordSecondWord += countInFirstMap * countInSecondMap // if both words occur in both word vectors calculate the product
              case None => // this case is not interesting
            }
          }
          if (dotProductFirstWordSecondWord > 0) {

            val lengthSecondWordVector = math.floor(scala.math.sqrt(math.floor(secondMap.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2)) * 100) / 100) * 100) / 100 // length of second word vector
            val cosOfAngleFirstWordSecondWord: Double = dotProductFirstWordSecondWord / (lengthFirstWordVector * lengthSecondWordVector) // cosAngle
            if (lengthSecondWordVector > 0 && lengthSecondWordVector > 0 && cosOfAngleFirstWordSecondWord > borderForCosAngle) { // filter results
              //println("firstWord: "+firstWord+"secondWord: "+secondWord)
              val tmp: ListMap[String, Double] = cosOfAngleMatrix(firstWord).updated(secondWord, (math floor cosOfAngleFirstWordSecondWord * 1000) / 1000)
              //cosOfAngleMatrix(firstWord)(secondWord) = (math floor cosOfAngleFirstWordSecondWord * 100) / 100
              cosOfAngleMatrix(firstWord) = tmp
            } else {

            }
          }
        }

        ////
        // POS ANNOTATION
        ////

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

        ////
        // filter POS tags that doesn't match to the main words POS tag
        ////


        cosOfAngleMatrix(firstWord) = ListMap(cosOfAngleMatrix(firstWord).toList.sortBy {
          _._2
        }.reverse: _*) // sort and get best 11 results
      val firstWordPOS: String = getPOS(firstWord)
        cosOfAngleMatrix(firstWord) = cosOfAngleMatrix(firstWord).filter(x => getPOS(x._1) == firstWordPOS).take(11)

        ////
        // calculate the relative cosine angle
        ////

        def relCosSimMatrix(cosOfAngleMap: Map[String, Double]): ListMap[String, Double] = {
          //if(cosOfAngleMap.size > 0){
          val returnValue: Map[String, Double] = (for (currentTuple <- cosOfAngleMap) yield {
            val cosineSimCurrent: Double = cosOfAngleMap(currentTuple._1)
            val sumCosineSimTop10: Double = cosOfAngleMap.reduce((tuple1, tuple2) => ("placeholder", tuple1._2 + tuple2._2))._2 - currentTuple._2
            if (sumCosineSimTop10 > 0) {
              (currentTuple._1, cosineSimCurrent / sumCosineSimTop10)
            } else {
              (currentTuple._1, 0.0)
            }
          }).filter(x => x._2 >= 0.11)
          ListMap(returnValue.toList.sortBy {
            _._2
          }.reverse: _*) // return ordered soultions
        }

        cosOfAngleMatrix(firstWord) = relCosSimMatrix(cosOfAngleMatrix(firstWord).filter(x => x._1 != firstWord))
        cosOfAngleMatrix.filter(x => x._2.nonEmpty).foreach(x => write(x + "\n"))
        cosOfAngleMatrix.empty
      }
      close()
    }
  }

  //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
  //  just reads a avro and return it
  //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  def readAvro(): Map[String, Map[String, Int]] = {

    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //  deserialize avro file with the java api
    //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    val coOccurrencesBuilder = Map.newBuilder[String, Map[String, Int]]
    val avroOutput: File = new File("data/coOccurrences.avro")
    //val avroOutput: File = new File("coOccurrences_big.avro")
    try {
      val bdPersonDatumReader = new SpecificDatumReader[wordList](classOf[wordList])
      val dataFileReader = new DataFileReader[wordList](avroOutput, bdPersonDatumReader)
      while (dataFileReader.hasNext) {
        import scala.collection.JavaConversions._
        val currentWord: wordList = dataFileReader.next
        coOccurrencesBuilder += Tuple2(currentWord.getWord.toString, currentWord.getCooc.toMap.map(x => (x._1.toString, x._2.toInt)))
      }
    } catch {
      case _: IOException =>
        System.out.println("Error reading Avro")
    }
    val coOccurrences: Map[String, Map[String, Int]] = coOccurrencesBuilder.result()
    val coOccurrenceSize = coOccurrences.size
    println("size AVRO: " + coOccurrenceSize)
    coOccurrences
  }

  //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
  //  calculate the distance of a document to another
  //  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  def calcDistanceOfDocs(doc: String): Unit = {
    val borderForCosAngle: Double = 0.0 // not important atm
    var doc1 = doc

    // get coOccurrences from avro file (takes a while)
    println("READ AVRO")
    val coOccurrences: Map[String, Map[String, Int]] = readAvro()
    println("READY READ AVRO")

    // load the stanford annotator for NER tagging and lemmatisation
    println("LOADING STANFORD PARSER")
    val props: Properties = new Properties() // set properties for annotator
    props.put("annotators", "tokenize, ssplit, pos, lemma, ner, regexner")
    props.put("regexner.mapping", "jg-regexner.txt")
    val pipeline: StanfordCoreNLP = new StanfordCoreNLP(props) // annotate file
    def getNER(sentence: String): String = { // get POS tags per sentence
      val document: Annotation = new Annotation(sentence)
      pipeline.annotate(document) // annotate
      val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
      val back = (for {
        sentence: CoreMap <- sentences
        token: CoreLabel <- sentence.get(classOf[TokensAnnotation]).asScala.toList
        _: String = token.get(classOf[TextAnnotation])
        _: String = token.get(classOf[PartOfSpeechAnnotation])
        lemma: String = token.get(classOf[LemmaAnnotation])
        regexner: String = token.get(classOf[NamedEntityTagAnnotation])

      } yield (lemma, regexner))
        // make this : "(New,location) (York,location) (is,0) (great,0) (.,0)" to this: "New_York is great"
        .reduceLeft((tupleFirst, tupleSecond) => {
        if (tupleFirst._2 == tupleSecond._2 && tupleSecond._2 != "O") {
          (tupleFirst._1 + "_" + tupleSecond._1, tupleSecond._2)
        } else {
          (" " + tupleFirst._1 + " " + tupleSecond._1, tupleSecond._2)
        }
      })._1
      println("NER & lemma: " + back)
      back
    }
    println("READY LOADING STANFORD PARSER")

    // get the accumulated vector

    def accumulatedDocumentVector(doc: String): Map[String, Int] = {
      getNER(doc) // get the NER and lemma
        .split(" ").flatMap(token => coOccurrences // get words which have an entry in the cooc List
        .get(token.toLowerCase())).flatten // filter None
        .groupBy(_._1) // ?
        .map { case (k, v) => (k, v.map(_._2).sum) // sum up the count of one word
      }
    }

    def lengthOfVector(vectorDoc: Map[String, Int]): Double = {
      math.floor(scala.math.sqrt(vectorDoc.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2))) * 100) / 100 // calc the length for the current word vector with two digit precision
    }

    var vectorDoc1: Map[String, Int] = accumulatedDocumentVector(doc1)
    var lengthFirstWordVector: Double = lengthOfVector(vectorDoc1)

    while (true) {
      println("")
      println("doc 1: " + doc1)
      print("set doc 2: ")
      val doc2: String = scala.io.StdIn.readLine() // ask for doc 2

      // possibility to change doc 1
      if (doc2 == "0") {
        print("set doc 1: ")
        doc1 = scala.io.StdIn.readLine() // ask for doc 1
        vectorDoc1 = accumulatedDocumentVector(doc1)
        lengthFirstWordVector = lengthOfVector(vectorDoc1)
      }
      helpMethod()

      def helpMethod(): Unit = {
        val cosOfAngleMatrix = scala.collection.mutable.Map[String, ListMap[String, Double]]() // we can save the distances to other word vectors here
        cosOfAngleMatrix("doc1") = ListMap[String, Double]() // make a entry for the current word

        val vectorDoc2: Map[String, Int] = accumulatedDocumentVector(doc2)
        val lengthSecondWordVector = math.floor(scala.math.sqrt(math.floor(vectorDoc2.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2)) * 100) / 100) * 100) / 100 // length of second word vector
        println("doc 1 # words: " + vectorDoc1.size)
        println("doc 2 # words: " + vectorDoc2.size)
        println("doc 1 lengthVector: " + lengthFirstWordVector)
        println("doc 2 lengthVector: " + lengthSecondWordVector)

        var dotProductFirstWordSecondWord: Int = 0 // initiate the dotproduct
        var countWordsInBothDocuments: Int = 0
        vectorDoc2.foreach { case (wordInSecondMap, countInSecondMap) => // get every word in the second word
          vectorDoc1.get(wordInSecondMap) match { // and look if this words are present in the first word
            case Some(countInFirstMap) =>
              dotProductFirstWordSecondWord += countInFirstMap * countInSecondMap // if both words occur in both word vectors calculate the product
              countWordsInBothDocuments += 1
            case None => // this case is not interesting
          }
        }

        println("doc 1 & doc 2 # words in both: " + countWordsInBothDocuments)
        println("doc 1 & doc 2 dotProduct: " + dotProductFirstWordSecondWord)


        val cosOfAngleFirstWordSecondWord: Double = dotProductFirstWordSecondWord / (lengthFirstWordVector * lengthSecondWordVector) // cosAngle
        if (lengthSecondWordVector > 0 && lengthSecondWordVector > 0 && cosOfAngleFirstWordSecondWord > borderForCosAngle) { // filter results
          //println("firstWord: "+firstWord+"secondWord: "+secondWord)
          val tmp: ListMap[String, Double] = cosOfAngleMatrix("doc1").updated("doc2", (math floor cosOfAngleFirstWordSecondWord * 1000) / 1000)
          //cosOfAngleMatrix(firstWord)(secondWord) = (math floor cosOfAngleFirstWordSecondWord * 100) / 100
          cosOfAngleMatrix("doc1") = tmp
          println("//dotProductFirstWordSecondWord / (lengthFirstWordVector * lengthSecondWordVector)")
          println("similarity 0 to 1: " + cosOfAngleFirstWordSecondWord)
        } else {

        }
        ////
        // calculate the relative cosine angle DON'T DELETE ME
        ////
        /*
      def relCosSimMatrix(cosOfAngleMap: Map[String, Double]): ListMap[String, Double] = {
        //if(cosOfAngleMap.size > 0){
        val returnValue: Map[String, Double] = (for (currentTuple <- cosOfAngleMap) yield {
          val cosineSimCurrent: Double = cosOfAngleMap(currentTuple._1)
          val sumCosineSimTop10: Double = cosOfAngleMap.reduce((tuple1, tuple2) => ("placeholder", tuple1._2 + tuple2._2))._2 - currentTuple._2
          if (sumCosineSimTop10 > 0) {
            (currentTuple._1, cosineSimCurrent / sumCosineSimTop10)
          } else {
            (currentTuple._1, 0.0)
          }
        }).filter(x => x._2 >= 0.11)
        ListMap(returnValue.toList.sortBy {
          _._2
        }.reverse: _*) // return ordered soultions
      }


      cosOfAngleMatrix("doc1") = relCosSimMatrix(cosOfAngleMatrix("doc1").filter(x => x._1 != "doc1"))*/
        cosOfAngleMatrix.empty
      }
    }

  }

  def calcDistanceOfDocsWithinElastic(doc: String,detailedPrint:Boolean)(implicit client: HttpClient): Unit = {
    val borderForCosAngle: Double = 0.0 // not important atm
    var doc1 = doc

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // get coOccurrences from avro file (takes a while)
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("READ AVRO")
    val coOccurrences: Map[String, Map[String, Int]] = readAvro()
    println("READY READ AVRO")

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // load the stanford annotator for NER tagging and lemmatisation
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("LOADING STANFORD PARSER")
    val props: Properties = new Properties() // set properties for annotator
    props.put("annotators", "tokenize, ssplit, pos, lemma, ner, regexner")
    props.put("regexner.mapping", "jg-regexner.txt")
    val pipeline: StanfordCoreNLP = new StanfordCoreNLP(props) // annotate file

    def getNER(sentence: String): String = { // get POS tags per sentence
      val document: Annotation = new Annotation(sentence)
      pipeline.annotate(document) // annotate
      val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
      val back = (for {
        sentence: CoreMap <- sentences
        token: CoreLabel <- sentence.get(classOf[TokensAnnotation]).asScala.toList
        _: String = token.get(classOf[TextAnnotation])
        _: String = token.get(classOf[PartOfSpeechAnnotation])
        lemma: String = token.get(classOf[LemmaAnnotation])
        regexner: String = token.get(classOf[NamedEntityTagAnnotation])

      } yield (lemma, regexner))
        // make this : "(New,location) (York,location) (is,0) (great,0) (.,0)" to this: "New_York is great"
        .reduceLeft((tupleFirst, tupleSecond) => {
        if (tupleFirst._2 == tupleSecond._2 && tupleSecond._2 != "O") {
          (tupleFirst._1 + "_" + tupleSecond._1, tupleSecond._2)
        } else {
          (" " + tupleFirst._1 + " " + tupleSecond._1, tupleSecond._2)
        }
      })._1
      //println("NER & lemma: " + back)
      back
    }
    println("READY LOADING STANFORD PARSER")

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // get the accumulated vector
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    def accumulatedDocumentVector(doc: String): Map[String, Int] = {
      getNER(doc) // get the NER and lemma
        .split(" ").flatMap(token => coOccurrences // get words which have an entry in the cooc List
        .get(token.toLowerCase())).flatten // filter None
        .groupBy(_._1) // ?
        .map { case (k, v) => (k, v.map(_._2).sum) // sum up the count of one word
      }
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // get length of this vector
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    def lengthOfVector(vectorDoc: Map[String, Int]): Double = {
      math.floor(scala.math.sqrt(vectorDoc.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2))) * 100) / 100 // calc the length for the current word vector with two digit precision
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // calc accumulated vector and length of doc1
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    var vectorDoc1: Map[String, Int] = accumulatedDocumentVector(doc1)
    var lengthFirstWordVector: Double = lengthOfVector(vectorDoc1)


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // sentence splitter
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    def ssplit(text:String): Seq[String] = {
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

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // get documents from elastic
    // - - - - - - - - - - - - - - - - - - - - - - - - -


    implicit val timeout: FiniteDuration = Duration(1000, "seconds")
    //val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("test") matchAllQuery() keepAlive (keepAlive = "10m") size 100 sourceInclude List("text.string"))

    val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("test") matchQuery ("nerNorm",doc1) minScore(15)  keepAlive (keepAlive = "10m") size 10 sourceInclude List("text.string","id"))


    ///// unimportant

    var countSentences = 0
    // var countBuckets: mutable.SortedMap[Double,Int] = mutable.SortedMap(0.0->0,0.1->0,0.2->0,0.3->0,0.4->0,0.5->0,0.6->0,0.7->0,0.8->0,0.9->0,1.0->0,1.1->0,1.2->0,1.3->0,1.4->0,1.5->0,1.6->0,1.7->0,1.8->0,1.9->0,2.0->0,2.1->0)
    var countList:mutable.ArrayBuffer[Double] = mutable.ArrayBuffer[Double]()
    ///// unimportant end


    iterator.foreach(searchhit => { // for each element in the iterator
      val sentences = ssplit(List(searchhit.sourceField("text").asInstanceOf[Map[String,String]].get("string")).flatten.head.toString).toVector
      //println(searchhit.sourceField("id"))
      sentences.foreach(doc2 =>{
        helpMethod()

        def helpMethod(): Unit = {

          val vectorDoc2: Map[String, Int] = accumulatedDocumentVector(doc2)
          val lengthSecondWordVector = math.floor(scala.math.sqrt(math.floor(vectorDoc2.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2)) * 100) / 100) * 100) / 100 // length of second word vector

          var dotProductFirstWordSecondWord: Int = 0 // initiate the dotproduct
          var countWordsInBothDocuments: Int = 0
          vectorDoc2.foreach { case (wordInSecondMap, countInSecondMap) => // get every word in the second word
            vectorDoc1.get(wordInSecondMap) match { // and look if this words are present in the first word
              case Some(countInFirstMap) =>
                dotProductFirstWordSecondWord += countInFirstMap * countInSecondMap // if both words occur in both word vectors calculate the product
                countWordsInBothDocuments += 1
              case None => // this case is not interesting
            }
          }

          if (lengthSecondWordVector > 0 && lengthSecondWordVector > 0) { // filter results
            val cosOfAngleFirstWordSecondWord: Double = (dotProductFirstWordSecondWord / (lengthFirstWordVector * math.pow(lengthSecondWordVector,0.7))) / 4.924577

            ///// unimportant
            //countBuckets((cosOfAngleFirstWordSecondWord*10).toInt.toDouble/10) += 1
            countSentences += 1
            countList.append(cosOfAngleFirstWordSecondWord)

            if(countSentences % 50 == 0){
              println("")
              println("average: "+countList.sum/countSentences)
              println("count: "+countSentences)
              println("")
              /*countBuckets.foreach(x=> {
                print(x._1+" ")
                val amountOfPipes = ((x._2.toDouble / countSentences.toDouble) * 200).toInt
                0.to(amountOfPipes).foreach(_ => print ("|"))
                println("")
              }
              )
              println("count: "+countSentences)
              println("")*/

            }
            ///// unimportant end

            if(cosOfAngleFirstWordSecondWord>0.75){
              if (detailedPrint){
                println("doc 1: "+ doc1)
                println("doc 2: \n" + doc2)
                println("doc 1 # words: " + vectorDoc1.size)
                println("doc 2 # words: " + vectorDoc2.size)
                println("doc 1 lengthVector: " + lengthFirstWordVector)
                println("doc 2 lengthVector: " + lengthSecondWordVector)
                println("doc 1 & doc 2 # words in both: " + countWordsInBothDocuments)
                println("doc 1 & doc 2 dotProduct: " + dotProductFirstWordSecondWord)
                println("//dotProductFirstWordSecondWord / (lengthFirstWordVector * lengthSecondWordVector)")
                println("similarity: " + cosOfAngleFirstWordSecondWord)
              }else{
                println("similarity: " + cosOfAngleFirstWordSecondWord)
                println("doc 2: \n" + doc2)
                println("")
              }
              println("")
            }

          } else{

          }
        }
      })
    })
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get NER representation of a sentence
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def getNER(sentence: String): String = { // get POS tags per sentence
    val document: Annotation = new Annotation(sentence)
    pipelineNER.annotate(document) // annotate
    val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
    val back = (for {
      sentence: CoreMap <- sentences
      token: CoreLabel <- sentence.get(classOf[TokensAnnotation]).asScala.toList
      _: String = token.get(classOf[TextAnnotation])
      _: String = token.get(classOf[PartOfSpeechAnnotation])
      lemma: String = token.get(classOf[LemmaAnnotation])
      regexner: String = token.get(classOf[NamedEntityTagAnnotation])

    } yield (lemma, regexner))
      // make this : "(New,location) (York,location) (is,0) (great,0) (.,0)" to this: "New_York is great"
        .reduceLeft((tupleFirst, tupleSecond) => {
      if(tupleFirst._1.equals("``") || tupleFirst._1.equals("")){
        (tupleSecond._1, tupleSecond._2)
      }else if(tupleFirst._2 == tupleSecond._2 && tupleSecond._2 != "O") {
        (tupleFirst._1 + "_" + tupleSecond._1, tupleSecond._2)
      } else {
        (" " + tupleFirst._1 + " " + tupleSecond._1, tupleSecond._2)
      }
    })._1.dropRight(2)
    //println("NER & lemma: " + back)
    // TODO WrappedArray(, , , , , , , this, be, a, test, as, well, ., '') there are to many whitespaces
    back.toLowerCase()
  }


  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get the accumulated vector
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def accumulatedDocumentVector(doc: String): (Map[String, Int],mutable.LinkedHashMap[String,Map[String, Int]]) = {

    val vectorsOfToken = mutable.LinkedHashMap[String,Map[String, Int]]()
    val token: Seq[String] = getNER(doc) // get the NER and lemma
      .split(" ")
    println(token)

      val tokenWithVectors: Seq[Option[Map[String, Int]]] = token.map(token => coOccurrences // get words which have an entry in the cooc List
      .get(token.toLowerCase()))

    token.zip(tokenWithVectors).foreach(Tuple =>{
      Tuple._2 match {
        case Some(x) => vectorsOfToken += (Tuple._1->x)
        case None => vectorsOfToken += (Tuple._1->Map(""->0))
      }
    })
    val back = tokenWithVectors.flatten.flatten // filter None
      .groupBy(_._1) // ?
      .map { case (k, v) => (k, v.map(_._2).sum) // sum up the count of one word
    }
    (back, vectorsOfToken)
  }


  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get length of this vector
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def lengthOfVector(vectorDoc: Map[String, Int]): Double = {
    math.floor(scala.math.sqrt(vectorDoc.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2))) * 100) / 100 // calc the length for the current word vector with two digit precision
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // sentence splitter
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def ssplit(text:String): Seq[String] = {
    //val text = Source.fromFile(filename).getLines.mkString
    val document: Annotation = new Annotation(text)
    // run all Annotator - Tokenizer on this text
    pipelineSplit.annotate(document)
    val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
    (for {
      sentence: CoreMap <- sentences
    } yield sentence).map(_.toString)
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // API
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def calcDistanceAPI(doc2: String): scala.collection.mutable.ArrayBuffer[Tuple2[Seq[Tuple2[String,Double]],Double]] = {
    val borderForCosAngle: Double = 0.0 // not important atm
    var cosOfAngleFirstWordSecondWord: Double = 0
    val returnList = scala.collection.mutable.ArrayBuffer[Tuple2[Seq[Tuple2[String,Double]],Double]]()

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    val sentences = ssplit(doc2).toVector
    //println(searchhit.sourceField("id"))

      sentences.foreach(doc2 =>{

        val vectorDoc2: Map[String, Int] = accumulatedDocumentVector(doc2)._1
        val vectorDoc2Token: mutable.LinkedHashMap[String,Map[String, Int]] = accumulatedDocumentVector(doc2)._2
        vectorDoc2Token

        def calcDist(vector: Map[String, Int]): Any = {
          val lengthSecondWordVector = math.floor(scala.math.sqrt(math.floor(vector.values.foldLeft(0.0)((x, y) => x + scala.math.pow(y, 2)) * 100) / 100) * 100) / 100 // length of second word vector

          var dotProductFirstWordSecondWord: Int = 0 // initiate the dotproduct
          var countWordsInBothDocuments: Int = 0
          vector.foreach { case (wordInSecondMap, countInSecondMap) => // get every word in the second word
            vectorDoc1.get(wordInSecondMap) match { // and look if this words are present in the first word
              case Some(countInFirstMap) =>
                dotProductFirstWordSecondWord += countInFirstMap * countInSecondMap // if both words occur in both word vectors calculate the product
                countWordsInBothDocuments += 1
              case None => // this case is not interesting
            }
          }

          if (lengthSecondWordVector > 0 && lengthSecondWordVector > 0) { // filter results
            cosOfAngleFirstWordSecondWord = (dotProductFirstWordSecondWord / (lengthFirstWordVector * math.pow(lengthSecondWordVector, 0.7))) / 4.924577
            println(cosOfAngleFirstWordSecondWord)
            math.ceil(cosOfAngleFirstWordSecondWord * 10000) / 10000

            /*
          countList.append(cosOfAngleFirstWordSecondWord)

          if(countSentences % 50 == 0){
            println("")
            println("average: "+countList.sum/countSentences)
            println("count: "+countSentences)
            println("")


          }
          ///// unimportant end
          if(cosOfAngleFirstWordSecondWord>0.75){
            if (detailedPrint){
              println("doc 1: "+ doc1)
              println("doc 2: \n" + doc2)
              println("doc 1 # words: " + vectorDoc1.size)
              println("doc 2 # words: " + vectorDoc2.size)
              println("doc 1 lengthVector: " + lengthFirstWordVector)
              println("doc 2 lengthVector: " + lengthSecondWordVector)
              println("doc 1 & doc 2 # words in both: " + countWordsInBothDocuments)
              println("doc 1 & doc 2 dotProduct: " + dotProductFirstWordSecondWord)
              println("//dotProductFirstWordSecondWord / (lengthFirstWordVector * lengthSecondWordVector)")
              println("similarity: " + cosOfAngleFirstWordSecondWord)
            }else{
              println("similarity: " + cosOfAngleFirstWordSecondWord)
              println("doc 2: \n" + doc2)
              println("")
            }
            println("")
          }*/
          }
        }

        val distToDoc2 = calcDist(vectorDoc2)
        if(distToDoc2.isInstanceOf[Double]){
          //List[Tuple2[String,Double]]
          val tokenAndDouble: mutable.LinkedHashMap[String, Double] = for (mapStringMap <- vectorDoc2Token ) yield {
            val distToToken = calcDist(mapStringMap._2)
            if(distToToken.isInstanceOf[Double]) {
              Tuple2(mapStringMap._1, distToToken.asInstanceOf[Double])
            }else{
              Tuple2(mapStringMap._1, 0.0)
            }
          }
          returnList.append(Tuple2(tokenAndDouble.toList, distToDoc2.asInstanceOf[Double]))
        }


      })
    print(returnList)
    returnList
  }
}
