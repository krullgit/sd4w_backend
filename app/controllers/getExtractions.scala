package de.tu_berlin.dima.code


import java.io.{File, FileNotFoundException, PrintWriter}
import java.nio.charset.Charset
import java.util.Properties

import com.google.common.io.Files
import com.sksamuel.elastic4s.{ElasticsearchClientUri, RefreshPolicy}
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.{SearchHit, SearchIterator}
import edu.stanford.nlp.ling.CoreAnnotations.{SentencesAnnotation, _}
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.util.CoreMap
import java.io.{FileInputStream, InputStream}

import opennlp.tools.chunker.ChunkerME
import opennlp.tools.chunker._

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.util.{Failure, Success, Try}
import opennlp.tools.chunker
import java.io.{FileInputStream, InputStream}

import cats.syntax.VectorOps
import opennlp.tools.chunker.ChunkerME
import opennlp.tools.chunker._

import scala.collection.mutable


class getExtractions(client: HttpClient, chunker: ChunkerME, pipelinePos: StanfordCoreNLP, pipelineNER: StanfordCoreNLP, pipelineSplit: StanfordCoreNLP) {

  implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method
  case class chunkAndExtractions(chunks: String = "", extractionTriple: Vector[String] = Vector(""), count: Int = 0)

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


  def chunking(sent: Array[String], pos: Array[String]): Array[String] = {
    val tag: Array[String] = chunker.chunk(sent, pos)


    tag
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


  def getPOS(sentence: String): Vector[(String, String)] = { // get POS tags per sentence
    val document: Annotation = new Annotation(sentence)
    pipelinePos.annotate(document) // annotate
    val sentences: Vector[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toVector
    for {
      sentence: CoreMap <- sentences
      token: CoreLabel <- sentence.get(classOf[TokensAnnotation]).asScala.toVector
      pos: String = token.get(classOf[PartOfSpeechAnnotation])

    } yield (pos, token.originalText()) // return List of POS tags
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def getListOfProducts(category: String, numberOfProducts: Int): Vector[String] = {
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method
    val listBuilder = Vector.newBuilder[String]


    def hereToServe(): Unit = {
      val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("amazon_reviews_meta") query fuzzyQuery("categories", category).fuzziness("1") keepAlive (keepAlive = "10m") size 1000) //sourceInclude List("text.string","id"))

      iterator.foreach(searchhit => {
        listBuilder += searchhit.sourceField("asin").toString
        if (listBuilder.result().size > numberOfProducts) return
      })
    }

    hereToServe()

    /*val resp = client.execute {
      search("amazon_reviews_meta" / "doc").keepAlive("1m").size(numberOfProducts) query fuzzyQuery("categories", category).fuzziness("1")
    }.await()
    resp match {

      case Right(results) => results.result.hits.hits.foreach(x => {
        listBuilder += x.sourceField("asin").toString
      })
    }*/
    client.close()

    listBuilder.result()

  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


  def saveAmazonReviewsInAFile(filename: String, listOfProducts: Vector[String]): Vector[String] = {
    val listOfListsOfProducts = listOfProducts.grouped(100).toList
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    val listBuilder = Vector.newBuilder[String]
    for (i <- listOfListsOfProducts.indices) {

      val resp = client.execute {
        search("amazon_reviews").keepAlive("1m").size(10000) query termsQuery("asin.keyword", listOfListsOfProducts(i))
      }.await
      resp match {

        case Right(results) => results.result.hits.hits.foreach(x => {
          listBuilder += x.sourceField("reviewText").toString
        })
      }
    }

    /*new PrintWriter(s"data/${filename}.txt") { // open new file
      listBuilder.result().foreach(x => write(x + "\n")) // write distinct list to file
      //iLiveToServe.distinct.sorted.map(ssplit(_)).flatten.foreach(x => write(x + "\n")) // write distinct list to file
      close // close file
    }*/


    client.close() // close HttpClient
    listBuilder.result()
    //listBuilder.result().mkString("\n")
  }


  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


  def ssplit(reviews: Vector[String]): Vector[String] = {

    // create blank annotator


    val back = Vector.newBuilder[String]
    reviews.foreach(review => {
      val document: Annotation = new Annotation(review)
      // run all Annotator - Tokenizer on this text
      pipelineSplit.annotate(document)
      val sentences: Vector[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toVector
      val backTmp: Vector[String] = (for {
        sentence: CoreMap <- sentences
      } yield (sentence)).map(_.toString)
      backTmp.foreach(x => back += x)

    })


    back.result()
  }

  def ssplit(review: String): Vector[String] = {

    val back = Vector.newBuilder[String]

    val document: Annotation = new Annotation(review)
    pipelineSplit.annotate(document)
    val sentences: Vector[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toVector
    val backTmp: Vector[String] = (for {
      sentence: CoreMap <- sentences
    } yield (sentence)).map(_.toString)
    backTmp.foreach(x => back += x)

    back.result()
  }


  def estimateRecall(filename: String, sentences: Vector[String]) = {
    val wordsLowerBorder = 3;
    val wordsUpperBound = 12;

    //val wordsLowerBorder = 13;
    //val wordsUpperBound = 30;

    //val text = Source.fromFile(filename).getLines.mkString


    ////////////////DEBUG///////////////



    var countOne: Double = 0
    var countMoreThanOne: Double = 0

    val sentencesFilteredSpeacialCharacters: Seq[String] = sentences.filter(x => {
      val hits = Vector.newBuilder[Int]
      hits += x.indexOf("?")
      hits += x.indexOf("(")
      hits += x.indexOf(")")
      //hits += x.indexOf(",")
      hits += x.indexOf(":")
      if (hits.result().max >= 0) false else true
    })

    // only sentences with "and", "but" or ","
    val sentFiltered = sentencesFilteredSpeacialCharacters.filter(x => {
      val hits = Vector.newBuilder[Int]
      hits += x.indexOf("and")
      hits += x.indexOf("but")
      hits += x.indexOf(",")
      if (hits.result().max < 0) {
        true
      } else {
        false
      }
    })

    //val sentSplitAtAnd = sentencesFilteredSpeacialCharacters.flatMap(x => x.split("([ ][a][n][d][ ])").map(x => x.trim))
    // val sentSplitAtBut = sentSplitAtAnd.flatMap(x => x.split("([ ][b][u][t][ ])").map(x => x.trim))
    // val sentSplitAtKomma = sentSplitAtBut.flatMap(x => x.split("([,]+)").map(x => x.trim))


    val sentFilteredSigns = sentencesFilteredSpeacialCharacters
      //.filter(x => x.size <= 80)
      .map(x => {
      val listOfIndices = scala.collection.mutable.ArrayBuffer[Int]()
      listOfIndices += x.indexOf("\"")
      listOfIndices += x.indexOf(".")
      //listOfIndices += x.indexOf("-")
      listOfIndices += x.indexOf("?")
      listOfIndices += x.indexOf("!")
      listOfIndices.filter(x => x < 0)
      if (!listOfIndices.filter(x => x > 0).isEmpty) {
        x.substring(0, listOfIndices.filter(x => x > 0).min)
      } else {
        x
      }
    })
      .map(x => x.split(" "))
      .filter(x => x.size <= wordsUpperBound && x.size >= wordsLowerBorder)
      .map(x => x.mkString(" "))


    new PrintWriter(s"data/${filename}_ssplit.txt") { // open new file
      sentFilteredSigns.foreach(x => write(x + "\n"))
      close // close file
    }

    val sentWithPos: Seq[Vector[(String, String)]] = sentFilteredSigns
      //.map(x=>x.split(" "))
      //.filter(x=>x.size==10)
      //.map(x=>x.mkString(" "))
      .map(getPOS(_)) // get POS tags


    new PrintWriter(s"data/${filename}_pos.txt") { // open new file
      sentWithPos.foreach(x => write(x + "\n"))
      close // close file
    }
    //helper1.foreach(x => listBuilder += x.size)



    //helper01.map(x=>x.map(x=>x._2))
    /*      val sentChunked: Seq[Vector[String]] = sentWithPos //
            .map(x=>x.map(x=>x._1)) // get just the POS
            .map(x=>x.map(x=>{
              val replacementV = Vector("VB","VBD","VBG","VBN","VBP","VBZ")
              val replacementN = Vector("NN","NNS","NNP","NNPS","PRP")
              val replacementR = Vector("RB","RBR","RBS")
              val replacementJ = Vector("JJ","JJR","JJS")
              if(replacementV.contains(x)){
                "V"
              }else if(replacementN.contains(x)){
                "N"
              }else if(replacementR.contains(x)){
                "R"
              }else if(replacementJ.contains(x)){
                "J"
              }else{
                x
              }
            }))*/



    val sentChunked: Seq[Array[String]] = sentWithPos.zipWithIndex
      .map { case (vectorOfTuples, i) => {

        chunking(vectorOfTuples.map(x => x._2).toArray, vectorOfTuples.map(x => x._1).toArray)
      }
      }

    val sentChunkedWithoutI = sentChunked.map(x => x.filter(x => x(0).toString != "I")) // I filter the I because I want to reduce every chun to one toke e.g. "big green house"

    new PrintWriter(s"data/${filename}_chunks.txt") { // open new file
      sentChunkedWithoutI.foreach(x => write(x.mkString(" ") + "\n"))
      close // close file
    }

    sentChunkedWithoutI.map(_.mkString(" ")) // make List of POS tags to String
      .groupBy(pos => pos) // groupby POS Tag strings
      .mapValues(_.size) // count occurrences
      .toSeq // order them
      .foreach(x => {
      if (x._2 == 1) {
        countOne += 1
      }
      else if (x._2 > 1) {
        countMoreThanOne += 1
      }
    })




  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def parseExtractions(filename: String) = {



    val inputFile1: File = new File("data/" + filename) // read from file
    val extr: Vector[String] = Files.toString(inputFile1, Charset.forName("UTF-8")).split("\n\n").toVector
    val justSentences = extr.map(x => x.split("\n")(0))


    var counter1 = 0
    val justChunks: Seq[Array[String]] = extr.map(sentAndExtr => {

      counter1 += 1
      val splitted = sentAndExtr.split("\n")(0)
      val pos = getPOS(splitted)
      val chunked: Array[String] = chunking(pos.map(x => x._2).toArray, pos.map(x => x._1).toArray)
      chunked
    })


    var counter2 = 0
    val extractions: Vector[List[List[String]]] = extr
      .map(_.split("\n")) // split the lines in one extraction part
      .map(_.toList)
      .map(extractionPart => extractionPart
        .map(line => {

          counter2 += 1
          val indexFirst = line.indexOf("(")
          val indexLast = line.lastIndexOf(")")

          if (indexFirst == -1 || indexLast == -1) {
            line.split(" ").toList // when the line is the original senence tokenize it
          } else {
            line.drop(indexFirst + 1).dropRight(line.size - indexLast).split(";").toList.map(x => x.trim)
              .filter(x => x != "") // filter triple  which are actually tuples
          }
        })
        .filter(line => !line.mkString(" ").contains("List([")) // filter all Context Extractions of OIE5
        //.filter(line => !line.mkString(" ").contains("T:"))// filter all time Extractions of OIE5
        .filter(line => !line.mkString(" ").contains("L:"))
      )


    var counter3 = 0

    val rulesAll = Vector.newBuilder[Vector[String]]
    val parsedExtractionsSize = extractions.size
    for (extractionIndex <- extractions.indices) {

      counter3 += 1

      val rules = Vector.newBuilder[String]

      //rules += (for (i <- parsedExtractions(extracionCounter)) yield {
      //  i._1
      //}).mkString(" ")


      for (lineNumberOfExtraction <- extractions(extractionIndex).indices) {
        var rule: String = ""

        if (lineNumberOfExtraction != 0) { // exclude the sentence itself (only extraction lines are considered)
          if (!(extractions(extractionIndex)(lineNumberOfExtraction)(0) == "No extractions found.")) { // ollie uses this line to indicate that there is no extracion
            if (extractions(extractionIndex)(lineNumberOfExtraction).size == 3) { // TODO: consider Context extractions of OIE5 (which occurs frequently actually)

              val tmp = extractions(extractionIndex)(lineNumberOfExtraction).mkString(" ").split(" ")
              var tmpbool = false

              tmp.foreach(x => {
                var countOccurenses = 0;
                if (extractions(extractionIndex)(0).count(_ == x) > 1) {
                  tmpbool = true
                }
              })



              if (!tmpbool) {

                for (partOfTriple <- extractions(extractionIndex)(lineNumberOfExtraction)) {
                  partOfTriple.split(" ").zipWithIndex.foreach { case (wordInExtracion, wordInExtracionIndex) => { // for every word in one triple part

                    val wordInExtracionCleaned = if (wordInExtracion.contains("T:")) {
                      wordInExtracion.drop(2)
                    } else wordInExtracion
                    var occurenceIndices: Int = 10000
                    var wordBeforeInSentence = ""
                    var wordAfterInSentence = ""
                    var wordBeforeInTriple = partOfTriple.split(" ").lift(wordInExtracionIndex - 1) match {
                      case Some(x) => x;
                      case None => ""
                    }
                    var wordAfterInTriple = partOfTriple.split(" ").lift(wordInExtracionIndex + 1) match {
                      case Some(x) => x;
                      case None => ""
                    }


                    extractions(extractionIndex)(0).zipWithIndex.foreach { case (wordInSentence, j) => if (wordInSentence == wordInExtracionCleaned) {
                      wordBeforeInSentence = extractions(extractionIndex)(0).lift(j - 1) match {
                        case Some(x) => x;
                        case None => ""
                      }
                      wordAfterInSentence = extractions(extractionIndex)(0).lift(j + 1) match {
                        case Some(x) => x;
                        case None => ""
                      }

                      if (occurenceIndices == 10000) {
                        if (wordBeforeInSentence == wordBeforeInTriple) {
                          occurenceIndices = j

                        } else if (wordAfterInSentence == wordAfterInTriple) {
                          occurenceIndices = j

                        } else {
                          occurenceIndices = j
                        }
                      } else {
                        if (wordBeforeInSentence == wordBeforeInTriple) {
                          occurenceIndices = j

                        } else if (wordAfterInSentence == wordAfterInTriple) {
                          occurenceIndices = j

                        }
                      }

                      //occurenceIndices += j+"["+wordInSentence+"]"

                    }
                    }

                    //val occurenceIndicesLast = occurenceIndices.result().last
                    //val indexOfTheSmallest = occurenceIndices.result().map(x=>math.abs(x-occurenceIndicesLast)).zipWithIndex.zipWithIndex.min._2

                    rule += occurenceIndices
                    if (extractions(extractionIndex)(0).mkString(" ").contains("period")) {




                    }

                    //rule += "#" + wordInExtracion // mark that this word accours more than one time (for later processing) TODO: fix that
                    if (wordInExtracionIndex >= partOfTriple.split(" ").size - 1) {
                      rule += ";"
                    } else {

                      rule += " "
                    }

                  }
                  }
                }
              }
            }
          }

        }
        rules += rule

        /*rulesAll.result().foreach(x=> {
          val extractions = x.tail.mkString(",")
          client.execute {
            indexInto("amazon_extractions" / "doc") fields
              "pattern" -> x.head,
              "extractions" -> extractions
            )
          }*/
      }
      rulesAll += rules.result()
    }


    val rules = rulesAll.result()



    val chunksAndExtractions = Vector.newBuilder[chunkAndExtractions]
    var chunksAndExtractionMissmatch = 0
    var misscreatedExtraction = 0





    var counter4 = 0

    //for(i <- 0 to 20){
    for (i <- 0 to rules.size - 1) {

      counter4 += 1


      val rulesOfSentence: Vector[String] = rules(i)
      val chunksOfSentence: Vector[String] = justChunks(i).toVector
      for (j <- 0 to rulesOfSentence.size - 1) {
        val ruleOfSentence: String = rulesOfSentence(j)
        val ruleOfSentenceTriple: Vector[String] = ruleOfSentence.split(";").toVector
          .filter(_ != "") // filter parts of triple which are empty
        val contains1000: Boolean = ruleOfSentence.contains("10000")
        if (ruleOfSentenceTriple.size == 3 && !contains1000) { // the triple must have 3 party otherwise its not a valid extraction

          var chunksOfSentenceUpdated: Vector[String] = chunksOfSentence
          var ruleOfSentenceTripleUpdated: Vector[String] = ruleOfSentenceTriple

          while (chunksOfSentenceUpdated.map(x => x(0).toString).contains("I")) {
            val chunksOfSentenceOnlyFirstChar: Vector[String] = chunksOfSentenceUpdated.map(x => x(0).toString)
            val indexOfI = chunksOfSentenceOnlyFirstChar.indexOf("I")


            chunksOfSentenceUpdated = chunksOfSentenceUpdated.take(indexOfI) ++ chunksOfSentenceUpdated.drop(indexOfI + 1)


            ruleOfSentenceTripleUpdated = ruleOfSentenceTripleUpdated.map(part => {

              val partSplitted = part.split(" ").filter(_ != "")
              var ruleOfSentenceTripleUpdatedPartAsVector: Vector[Int] = Vector[Int]()

              if (part.split(" ")(0) != "") {
                ruleOfSentenceTripleUpdatedPartAsVector = part.split(" ").map(_.toInt).toVector
              } else {
                chunksAndExtractionMissmatch += 1
              }

              val ruleOfSentenceTripleUpdatedPartAsVectorUpdated = ruleOfSentenceTripleUpdatedPartAsVector.filter(x => {
                if (x == indexOfI) false else true
              }).map(x => {
                if (x >= indexOfI) x - 1 else x
              })



              ruleOfSentenceTripleUpdatedPartAsVectorUpdated.mkString(" ")
            })
          }

          if (!ruleOfSentenceTripleUpdated.contains("")) {


            /*if(chunksOfSentenceUpdated.mkString("$") == "B-NP$B-VP$B-NP$B-PP$B-NP"){


            }*/

            chunksAndExtractions += chunkAndExtractions(chunksOfSentenceUpdated.mkString("$"), ruleOfSentenceTripleUpdated)
          } else {
            misscreatedExtraction += 1
          }
        }
      }
    }




    /*
    new PrintWriter(s"data/test3.txt") { // open new file
      for(i <- 0 to rulesAll.result().size-1){
        write(""+extractions(i)(0) + "\n")
        write(""+extrChunked(i).mkString(" ") + "\n")
        write(""+rulesAll.result()(i).mkString(" | ") + "\n\n")
      }
      close // close file
    }*/

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // take care of duplicated extraction rules
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    val chunksAndExtractionsResult: Vector[chunkAndExtractions] = chunksAndExtractions.result()
    var chunksAndExtractionsDisjunct = Vector[chunkAndExtractions]()
    var counter5 = 0
    for (i <- 0 until chunksAndExtractionsResult.size) {

      counter5 += 1
      val chunksAndExtraction = chunkAndExtractions(chunksAndExtractionsResult(i).chunks, chunksAndExtractionsResult(i).extractionTriple, 1)
      var chunkFoundIndex: Int = -1
      var chunkFoundExtraction = None: Option[chunkAndExtractions]
      for (j <- 0 until chunksAndExtractionsDisjunct.size) {
        val chunksAndExtractionDisjunkt = chunksAndExtractionsDisjunct(j)
        if (chunksAndExtraction.chunks == chunksAndExtractionDisjunkt.chunks && chunksAndExtraction.extractionTriple.mkString("") == chunksAndExtractionDisjunkt.extractionTriple.mkString("")) {
          chunkFoundIndex = j
          chunkFoundExtraction = Some(chunksAndExtractionDisjunkt)
        }
      }
      chunkFoundExtraction match {
        case None => chunksAndExtractionsDisjunct = chunksAndExtractionsDisjunct ++ Vector(chunksAndExtraction)
        case Some(x) => {
          val chunk = x.chunks
          val extractionTriple = x.extractionTriple
          val newCount: Int = chunksAndExtractionsDisjunct(chunkFoundIndex).count + 1
          chunksAndExtractionsDisjunct = chunksAndExtractionsDisjunct.updated(chunkFoundIndex, chunkAndExtractions(chunk, extractionTriple, newCount))
        }
      }
    }
    indexExtractions("3", chunksAndExtractionsDisjunct)

  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def indexExtractions(filename: String, chunksAndExtractions: Vector[chunkAndExtractions]): Unit = {

    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    client.execute {
      deleteIndex(("amazon_extractions_" + filename))
    }
    client.execute {
      createIndex(("amazon_extractions_" + filename)) mappings (
        mapping("doc") as(
          keywordField("chunkPattern"),
          keywordField("extraction"),
          intField("count")
        ))
    }

    var counter6 = 0

    for (i <- 0 to chunksAndExtractions.size - 1) {

      counter6 += 1
      val chunkAndExtraction = chunksAndExtractions(i)

      client.execute {
        indexInto(("amazon_extractions_" + filename) / "doc").fields(
          "pattern" -> chunkAndExtraction.chunks,
          "extractions" -> chunkAndExtraction.extractionTriple.mkString(","),
          "count" -> chunkAndExtraction.count
        )
      }.await
    }
    client.close()
  }

  def testExtraction: Unit = {


    while (true) {
      print("Sentence: ")
      val sentence: String = scala.io.StdIn.readLine()
      val extracted = extract(sentence)
      for(i <- 0 until extracted._1.size){
        val extraction = extracted._1(i)
        val count = extracted._2(i)

      }


    }
  }


  def extract(sentence: String): (Vector[(String, String, String)], Vector[Int]) = {
    val extractionsBack = Vector.newBuilder[(String,String,String)]
    val extractionsCountBack = Vector.newBuilder[Int]

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    //  get NER Annotation
    //  another advantage here is that the ner tagger gives us correct tokenization (difficult cases like Mar., -> Vektor(DATE,,))
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    //(sentenceAsVector, nerVectorResult, tokenVectorResult)
    val getner = getNER(sentence)
    val sentenceSplitted: Vector[String] = getner._1.filterNot(x => x == "." || x == "?" || x == "!" || x == "'s" || x == "’s") // TODO: just filtering the "'s" might not be sufficient in every case e.g. when we whant to show properties | POS tag of 's is "POS" | but nevertheless important for corrent sentence chunking see "Bsp 1.:"
    val tokenVector = getner._3
    val nerVector = getner._2



    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // divide the sentence in sentence parts (splitted by commas)
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    val sentenceSplittedComma: Vector[String] = sentenceSplitted.mkString(" ").split(",").map(_.trim).toVector

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // make combinations of that sentence parts and send each to the extraction function
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    if (sentenceSplittedComma.size > 1) {
      //if (false) {
      val sentenceCombinations = sentenceSplittedComma.toSet[String].subsets().map(_.toVector).toVector // get all combination of sentence-parts

      for (combi <- sentenceCombinations) {
        if (combi.size == 2) { // process only part-senetces in tuples or alone

          val sentence = combi.mkString(" , ") // mk a sent out of the combination


          val sentenceSplitted: Vector[String] = sentence.split(" ").toVector

          if (sentenceSplitted.size > 0) {
            var extractionFinished = 0
            var dropRight = 0
            while (extractionFinished == 0 && sentenceSplitted.dropRight(dropRight).size > 6) {
              getExtractions(sentenceSplitted.dropRight(dropRight), pipelineNER)
              extractionFinished = extractionsBack.result().size
              dropRight += 1
            }
          }

          // this is the case that we have to try the inversed order of the sentence pairs

          val sentence2 = combi.reverse.mkString(" , ") // mk a sent out of the combination


          val sentenceSplitted2: Vector[String] = sentence2.split(" ").toVector

          if (sentenceSplitted.size > 0) {
            var extractionFinished = 0
            var dropRight = 0
            while (extractionFinished == 0 && sentenceSplitted.dropRight(dropRight).size > 6) {
              getExtractions(sentenceSplitted.dropRight(dropRight), pipelineNER)
              extractionFinished = extractionsBack.result().size
              dropRight += 1
            }
          }
        } else if (combi.size == 1) {

          val sentence = combi.mkString(" , ") // mk a sent out of the combination


          var sentenceSplitted: Vector[String] = sentence.split(" ").toVector



          if (sentenceSplitted.size > 0) {
            var extractionFinished = 0
            var dropRight = 0
            while (extractionFinished == 0 && sentenceSplitted.dropRight(dropRight).size > 6) {
              getExtractions(sentenceSplitted.dropRight(dropRight), pipelineNER)
              extractionFinished = extractionsBack.result().size
              dropRight += 1
            }

          }
        }
      }
    } else {
      if (sentenceSplitted.size > 0) {
        var extractionFinished = 0

        var dropRight = 0
        while (extractionFinished == 0 && sentenceSplitted.dropRight(dropRight).size > 3) { // TODO make this more efficient
          getExtractions(sentenceSplitted.dropRight(dropRight), pipelineNER)
          extractionFinished = extractionsBack.result().size
          dropRight += 1
        }
      }
      //getExtractions(Vector(sentence))
    }



    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // takes one sentence and produce extractions
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    def getExtractions(sentenceSplitted: Vector[String], pipelineNER: StanfordCoreNLP): Unit = {
      implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method

      // TODO: make this more efficient

      //if(sentenceSplitted(0) == "who" || sentenceSplitted(0) == "Who" || sentenceSplitted(0) == "Why"|| sentenceSplitted(0) == "why"|| sentenceSplitted(0) == "how"|| sentenceSplitted(0) == "How"|| sentenceSplitted(0) == "What"|| sentenceSplitted(0) == "what"|| sentenceSplitted(0) == "Where"|| sentenceSplitted(0) == "where"|| sentenceSplitted(0) == "When"|| sentenceSplitted(0) == "when"|| sentenceSplitted(0) == "Which"|| sentenceSplitted(0) == "which"){
      //    return -2
      //}

      var sentenceSplittedWithoutComma = Vector[String]()

      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // apply the sentence chunker
      // - - - - - - - - - - - - - - - - - - - - - - - - -



      //val sentenceChunked: Vector[String] = sentence.split(" ").toVector



      val pos: Vector[(String, String)] = getPOS(sentenceSplitted.mkString(" "))

      var chunkedTags: Vector[String] = chunking(pos.map(x => x._2).toArray, pos.map(x => x._1).toArray).toVector


      // I filter the comma after the chunks are correctly built
      // This might be not very efficient since we have to filter the sentence and the chunk list as well
      // TODO: make this more efficient
      val positionOfComma = sentenceSplitted.indexOf(",")
      if (positionOfComma >= 0) {

        chunkedTags = chunkedTags.zipWithIndex.filter(_._2 != positionOfComma).map(_._1)
        sentenceSplittedWithoutComma = sentenceSplitted.zipWithIndex.filter(_._2 != positionOfComma).map(_._1)
      } else {
        sentenceSplittedWithoutComma = sentenceSplitted
      }




      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // bring chunks and chunk annotations in a shortened form
      // - - - - - - - - - - - - - - - - - - - - - - - - -


      val chunkedTagsReduced = Vector.newBuilder[String]
      val chunkedSentenceReduced = Vector.newBuilder[String]

      chunkedTagsReduced += chunkedTags(0)
      var chunkedSentenceReducedTMP: String = sentenceSplittedWithoutComma(0)

      for (i <- 1 until chunkedTags.size) {
        if (chunkedTags(i)(0).toString != "I") {
          chunkedTagsReduced += chunkedTags(i)
          chunkedSentenceReduced += chunkedSentenceReducedTMP
          chunkedSentenceReducedTMP = ""
          chunkedSentenceReducedTMP += " " + sentenceSplittedWithoutComma(i)
        } else {
          chunkedSentenceReducedTMP += " " + sentenceSplittedWithoutComma(i)
        }
      }

      chunkedSentenceReduced += chunkedSentenceReducedTMP
      val chunkedTagsReducedResult: Vector[String] = chunkedTagsReduced.result()
      if (chunkedTagsReducedResult.size <= 2) return extractionsBack.result()
      var chunkedSentenceReducedResult: Vector[String] = chunkedSentenceReduced.result().map(_.trim)




      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // replace the NER tags in the sentence with the original words
      // - - - - - - - - - - - - - - - - - - - - - - - - -

      val chunkedSentenceReducedResultXY = chunkedSentenceReducedResult.map(chunk => {

        var chunkSplitted = chunk.split(" ").toVector
        for (i <- 0 until nerVector.size) {
          val ner = nerVector(i)
          val index = chunkSplitted.indexOf(ner)


          if (index >= 0) {
            chunkSplitted = chunkSplitted.updated(index, tokenVector(i))


          }
        }
        chunkSplitted.mkString(" ")
      })







      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // qery elasticsearch for extraction rules (ordered by extraction quality)
      // - - - - - - - - - - - - - - - - - - - - - - - - -


      val extractions = Vector.newBuilder[String]
      val extractionsCount = Vector.newBuilder[Int]
      val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("amazon_extractions_2") sortByFieldDesc ("count") query termQuery("pattern.keyword", chunkedTagsReducedResult.mkString("$")) keepAlive (keepAlive = "10m") size 1000) //sourceInclude List("text.string","id"))
      iterator.foreach(searchhit => {
        extractions += searchhit.sourceField("extractions").toString
        extractionsCount += searchhit.sourceField("count").asInstanceOf[Int]
      })

      val iterator3: Iterator[SearchHit] = SearchIterator.hits(client, search("amazon_extractions_3") sortByFieldDesc ("count") query termQuery("pattern.keyword", chunkedTagsReducedResult.mkString("$")) keepAlive (keepAlive = "10m") size 1000) //sourceInclude List("text.string","id"))
      iterator3.foreach(searchhit => {
        extractions += searchhit.sourceField("extractions").toString
        extractionsCount += searchhit.sourceField("count").asInstanceOf[Int]
      })

      val iterator2: Iterator[SearchHit] = SearchIterator.hits(client, search("amazon_extractions_1") sortByFieldDesc ("count") query termQuery("pattern.keyword", chunkedTagsReducedResult.mkString("$")) keepAlive (keepAlive = "10m") size 1000) //sourceInclude List("text.string","id"))
      iterator2.foreach(searchhit => {
        extractions += searchhit.sourceField("extractions").toString
        extractionsCount += searchhit.sourceField("count").asInstanceOf[Int]
      })


      val extractionsCountResult = extractionsCount.result()




      // Martin is the little brother of Carla
      //Martin is with Julia
      // For this reason I bought the watch

      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // print extraction in dependency to the quality (count) of them
      // - - - - - - - - - - - - - - - - - - - - - - - - -


      if (extractions.result().size > 0) { // is there a result
      case class extractionClass(part1: Vector[Int], part2: Vector[Int], part3: Vector[Int])
        for (i <- 0 until (if (extractions.result().size > 1) 2 else extractions.result().size)) { // give maximum 2 extractions
          //for(i <- 0 until extractions.result().size){ // give maximum 2 extractions
          //print("[" + extractionsCountResult(i) + "] ")
          //if(extractionsCount.result()(i)>20){ // is the count big enough
          val temp = extractions.result()(i).split(",")
          //.map(x=>x.split(" ").toVector).toVector
          val extraction: extractionClass = extractionClass(temp(0).split(" ").map(_.toInt).toVector, temp(1).split(" ").map(_.toInt).toVector, temp(2).split(" ").map(_.toInt).toVector)

          if (extraction.part1.max > chunkedSentenceReducedResultXY.size - 1 || extraction.part2.max > chunkedSentenceReducedResultXY.size - 1 || extraction.part3.max > chunkedSentenceReducedResult.size - 1) {

          } else {
            extractionsCountBack += extractionsCountResult(i)
            val extractionsBackTMP = Vector.newBuilder[String]
            var tmp = ""
            extraction.part1.foreach(x => {
              tmp += chunkedSentenceReducedResultXY(x) + " "
            })
            extractionsBackTMP += tmp.trim
            tmp = ""
            extraction.part2.foreach(x => {
              tmp += chunkedSentenceReducedResultXY(x) + " "
            })
            extractionsBackTMP += tmp.trim
            tmp = ""
            extraction.part3.foreach(x => {
              tmp += chunkedSentenceReducedResultXY(x) + " "
            })
            extractionsBackTMP += tmp.trim
            val extractionsBackTMPResult = extractionsBackTMP.result()
            extractionsBack += Tuple3(extractionsBackTMPResult(0),extractionsBackTMPResult(1),extractionsBackTMPResult(2))
          }
        }
      }
    }
    Tuple2(extractionsBack.result(),extractionsCountBack.result())
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // this function delivers a vector with sentence token (replaced with NER tags) and a Vector with words and their corresponding NER replacements
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def getNER(sentence: String): (Vector[String], Vector[String], Vector[String]) = { // get POS tags per sentence

    val document: Annotation = new Annotation(sentence)
    pipelineNER.annotate(document) // annotate
    val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
    val a = for {
      sentence: CoreMap <- sentences
      token: CoreLabel <- sentence.get(classOf[TokensAnnotation]).asScala.toList
      lemma: String = token.get(classOf[LemmaAnnotation])
      regexner: String = token.get(classOf[NamedEntityTagAnnotation])

    } yield (token.originalText(), lemma, regexner, token.index())

    // return Values
    var sentenceAsVector: Vector[String] = a.map(x => x._1).toVector

    val indexInOriginalSentence = Vector.newBuilder[Int]
    val nerVector = Vector.newBuilder[String]
    val tokenVector = Vector.newBuilder[String]
    var risingInt = 0 // this value is appended to every NER tag, so DATE becomes to DATE1 to ensure its uniqueness

    for (tokenAndNer <- a) {
      val ner: String = tokenAndNer._3
      if (ner != "O") {
        risingInt = risingInt + 1
        indexInOriginalSentence += tokenAndNer._4
        nerVector += "" + ner + risingInt
        tokenVector += tokenAndNer._1
      }
    }

    val indexInOriginalSentenceResult = indexInOriginalSentence.result().map(x => x - 1)

    // return Values
    val tokenVectorResult = tokenVector.result()
    val nerVectorResult = nerVector.result()

    for (i <- 0 until nerVectorResult.size) {
      val ner = nerVectorResult(i)
      val index = indexInOriginalSentenceResult(i)
      sentenceAsVector = sentenceAsVector.updated(index, ner)
    }




    (sentenceAsVector, nerVectorResult, tokenVectorResult)
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


}
