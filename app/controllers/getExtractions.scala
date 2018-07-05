package de.tu_berlin.dima.code


import java.io.{File, PrintWriter}
import java.nio.charset.Charset

import com.google.common.io.Files
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.{SearchHit, SearchIterator}
import com.sksamuel.elastic4s.indexes.IndexDefinition
import edu.stanford.nlp.ling.CoreAnnotations.{SentencesAnnotation, _}
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.util.CoreMap
import opennlp.tools.chunker.{ChunkerME, ChunkerModel}

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.duration.Duration


class getExtractions(client: HttpClient, pipelinePos: StanfordCoreNLP, pipelineNER: StanfordCoreNLP, pipelineSplit: StanfordCoreNLP, pipelineDep: StanfordCoreNLP, model: ChunkerModel) {

  implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method
  case class chunkAndExtractions(chunks: String = "", extractionTriple: Vector[String] = Vector(""), count: Int = 0, precision: String = "", id: Int = 0, sentencesIndex: String = "")

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


  def chunking(sent: Array[String], pos: Array[String]): Array[String] = {
    val chunker: ChunkerME = new ChunkerME(model)
    val tag: Array[String] = chunker.chunk(sent, pos)
    //println(tag.zip(sent).foreach(println(_)))
    //println(tag.mkString(" "))
    tag
  }

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
  // this is just for test case since we do not need dep parse
  // why is this 10 times faster than pos tagging actually?
  // - - - - - - - - - - - - - - - - - - - - - - - - -
  /*
    def getDEP(sentence: String) = { // get POS tags per sentence
      // create a document object
      val document: CoreDocument = new CoreDocument(sentence)
      // annnotate the document
      pipelineDep.annotate(document)
      // examples

      // second sentence
      val sentence2:CoreSentence = document.sentences().get(0);

      val dependencyParse = sentence2.coreMap()
      println("SENTENCE: "+sentence)
      println("PARSE:")
      println(dependencyParse)
      println("")
    }
  */
  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // Nothing to improve

  def getListOfProducts(category: String, numberOfProducts: Int): Vector[String] = {
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method
    val listBuilder = Vector.newBuilder[String]


    def hereToServe(): Unit = {
      //val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("amazon_reviews_meta") query fuzzyQuery("categories", category).fuzziness("1") keepAlive (keepAlive = "10m") size 1000) //sourceInclude List("text.string","id"))
      //println("We could fetch theoretically "+iterator.length+" product IDs") // 400000
      val iterator2: Iterator[SearchHit] = SearchIterator.hits(client, search("amazon_reviews_meta") query fuzzyQuery("categories", category).fuzziness("1") keepAlive (keepAlive = "10m") size 1000) //sourceInclude List("text.string","id"))
      iterator2.foreach(searchhit => {
        listBuilder += searchhit.sourceField("asin").toString
        if (listBuilder.result().size > numberOfProducts) return
      })
    }

    hereToServe()

    /*val resp = client.execute {
      search("amazon_reviews_meta" / "doc").keepAlive("1m").size(numberOfProducts) query fuzzyQuery("categories", category).fuzziness("1")
    }.await()
    resp match {
      case Left(failure) => println("We failed " + failure.error)
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
  // Nothing to improve

  def getVectorOfRawReviews(filename: String, listOfProducts: Vector[String]): Vector[String] = {
    val listOfListsOfProducts = listOfProducts.grouped(100).toList
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    val reviews = Vector.newBuilder[String]
    for (i <- listOfListsOfProducts.indices) {
      if (i % 10 == 0) {
        println("the reviews of how many products we got: " + i * 100)
      }
      val resp = client.execute {
        search("amazon_reviews").keepAlive("1m").size(10000) query termsQuery("asin.keyword", listOfListsOfProducts(i))
      }.await
      resp match {
        case Left(failure) => println("We failed " + failure.error)
        case Right(results) => results.result.hits.hits.foreach(x => {
          reviews += x.sourceField("reviewText").toString
        })
      }
    }

    // We could write the reviews in a file here
    /*new PrintWriter(s"data/${filename}.txt") { // open new file
      listBuilder.result().foreach(x => write(x + "\n")) // write distinct list to file
      //iLiveToServe.distinct.sorted.map(ssplit(_)).flatten.foreach(x => write(x + "\n")) // write distinct list to file
      close // close file
    }*/


    client.close() // close HttpClient
    reviews.result()
    //listBuilder.result().mkString("\n")
  }


  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // Nothing to improve

  def ssplit(reviews: Vector[String]): Vector[String] = {

    // create blank annotator
    println("start ssplit")

    val back = Vector.newBuilder[String]
    reviews.foreach(review => {
      val document: Annotation = new Annotation(review)
      // run all Annotator - Tokenizer on this text
      pipelineSplit.annotate(document)
      val sentences: Vector[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toVector
      val backTmp: Vector[String] = (for {
        sentence: CoreMap <- sentences
      } yield (sentence)).map(_.toString)
      // Backtmp contains a Vector with all sentences of a review
      // Now, we want to put each of these sentences in the final return Vector
      backTmp.foreach(x => back += x)
      if (back.result().size % 10000 == 0) println("splitted: " + back.result().size)
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


  def storeSentences(filename: String, sentences: Vector[String], maxRedundantChunkPattern: Int, wordsLowerBorder: Int, wordsUpperBound: Int) = {

    // - - - - - - - - - - -
    // We filter all sentences which do contain one of the following characters
    // - - - - - - - - - - -

    println("Filter ? ( ) :")
    val sentencesFilteredSpeacialCharacters: Seq[String] = sentences.filter(x => {
      val hits = Vector.newBuilder[Int]
      hits += x.indexOf("?")
      hits += x.indexOf("(")
      hits += x.indexOf(")")
      //hits += x.indexOf(",")
      hits += x.indexOf(":")
      if (hits.result().max >= 0) false else true
    })

    // - - - - - - - - - - -
    // only sentences with "and", "but" or ","
    // I think I do not need it, since I do not distinguish between conjunctions
    // - - - - - - - - - - -

    /*
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
        })*/

    // - - - - - - - - - - -
    // Here is the possibility to split the sentences at conjunction points.
    // - - - - - - - - - - -

    // val sentSplitAtAnd = sentencesFilteredSpeacialCharacters.flatMap(x => x.split("([ ][a][n][d][ ])").map(x => x.trim))
    // val sentSplitAtBut = sentSplitAtAnd.flatMap(x => x.split("([ ][b][u][t][ ])").map(x => x.trim))
    // val sentSplitAtKomma = sentSplitAtBut.flatMap(x => x.split("([,]+)").map(x => x.trim))

    // - - - - - - - - - - -
    // Trim sentences like .... see below
    // & remain only sentences within the length border
    // - - - - - - - - - - -

    println("Filter sentences out of the length threshold")
    val sentFilteredSigns: Seq[String] = sentencesFilteredSpeacialCharacters
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
      }).map(_ + ".")
      .map(x => x.split(" "))
      .filter(x => x.size <= wordsUpperBound && x.size >= wordsLowerBorder)
      .map(x => x.mkString(" "))
    println("S I Z E - S E N T E N C E S  B E T W E E N  " + wordsLowerBorder + " & " + wordsUpperBound + "  W O R D S: " + sentFilteredSigns.size)

    // - - - - - - - - - - -
    // just test scenario for dep parsing
    // - - - - - - - - - - -

    /*
    var counterDEP = 0
    val sentWithDep = sentFilteredSigns
      .map(sent => {
        counterDEP += 1
        if (counterDEP % 1000 == 0) println("DEP tagged: " + counterDEP)
        getDEP(sent)
      }) // get POS tags
    */

    // - - - - - - - - - - -
    // This POS annotation is for only used for sentence chunking below
    // - - - - - - - - - - -

    println("start pos")
    var counterPOS = 0
    val sentWithPos: Seq[Vector[(String, String)]] = sentFilteredSigns
      .map(sent => {
        counterPOS += 1
        if (counterPOS % 10000 == 0) println("POS tagged: " + counterPOS)
        getPOS(sent)
        // like this:
        // Seq(Vector((PRP,It), (VBZ,is), (CC,neither), (DT,a), (NN,tree), (CC,nor), (DT,a), (NN,car), (.,.)),)
      })


    // - - - - - - - - - - -
    // I tried here to cluster the sentences with POS tagging and therefore consolidaten Verbs, Nouns ... see below
    // Maybe it is usefull to build a charts for the thesis out of the perfomance gained by this consolidation
    // - - - - - - - - - - -

    //helper1.foreach(x => listBuilder += x.size)
    //println("size -> occurences: " + listBuilder.result().groupBy(identity).mapValues(_.size))
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

    // - - - - - - - - - - -
    // Here I chunk the sentences in order to cluster them
    // - - - - - - - - - - -

    println("start chunking")
    val sentChunked: Seq[Array[String]] = sentWithPos.zipWithIndex
      .map {
        case (vectorOfTuplesPOSToken, i) => {
          if (i % 10000 == 0) println("chunked: " + i)

          val pos: Array[String] = vectorOfTuplesPOSToken.map(x => x._1).toArray
          val token: Array[String] = vectorOfTuplesPOSToken.map(x => x._2).toArray

          val chunked: Array[String] = chunking(token, pos)
          val chunkedReplacedO = chunked.zipWithIndex.map { case (chunk, i) => {
            if (chunk == "O") {
              pos(i)
            } else {
              chunk
            }
          }
          }
          chunkedReplacedO
        }
      }

    // - - - - - - - - - - -
    // I group the chunk peaces e.g. "big green house"
    // - - - - - - - - - - -

    println("group the chunk peaces e.g. \"big green house\"")
    val sentChunkedWithoutI: Seq[String] = sentChunked.map(x => x.filter(x => x(0).toString != "I").mkString(" ")).map(_.mkString(" ")) // I filter the I because I want to reduce every chun to one toke e.g. "big green house"
    /*new PrintWriter(s"data/${filename}_chunks.txt") { // open new file
      sentChunkedWithoutI.foreach(x => write(x + "\n"))
      close // close file
    }*/

    // - - - - - - - - - - -
    // We count the chunks patterns which occurred in the reviews
    // - - - - - - - - - - -

    println("count the chunks patterns which occurred in the reviews")
    val sentChunkedWithoutICount: Vector[(String, Int)] = sentChunkedWithoutI // make List of POS tags to String
      .groupBy(chunk => chunk) // groupby POS Tag strings
      .mapValues(_.size) // count occurrences
      .toVector

    // - - - - - - - - - - -
    // We just want to retain "maxRedundantChunkPattern" of those sentences, which chunk patterns are the same.
    // These sentences are saved in "sentFinal"
    // - - - - - - - - - - -

    var counterRedundant = 0
    println("We just want to retain \"maxRedundantChunkPattern\" of those sentences, which chunk patterns are the same.")
    val chunkCountChunk: Seq[String] = sentChunkedWithoutICount.map(x => x._1)
    var chunkCountCountFinished: immutable.Seq[Int] = Vector.fill(chunkCountChunk.size)(0)
    val sentFinal = Vector.newBuilder[String]
    sentChunkedWithoutI.zipWithIndex.foreach(sentAndIndex => {
      counterRedundant += 1
      if (counterRedundant % 10000 == 0) println("counterRedundant: " + counterRedundant)
      val sentChunks: String = sentAndIndex._1
      val index: Int = sentAndIndex._2
      val sent: String = sentFilteredSigns(index)
      val positionOfChunk: Int = chunkCountChunk.indexOf(sentChunks)
      if (positionOfChunk >= 0) {
        val countFinshed = chunkCountCountFinished(positionOfChunk)
        if (countFinshed < maxRedundantChunkPattern) {
          sentFinal += sent
          chunkCountCountFinished = chunkCountCountFinished.updated(positionOfChunk, countFinshed + 1)
        } else {
        }
      } else {
        println("THIS IS BAD")
      }
    })

    // - - - - - - - - - - -
    // Show sentences with the same chunk pattern just for test cases
    // - - - - - - - - - - -

    /*
    for(i <- 0 until sentChunkedWithoutICount.size){
      val chunkCount = sentChunkedWithoutICount(i)._2
      if(chunkCount>1){
        println("chunkCount: "+chunkCount)
        val chunkpattern: String = sentChunkedWithoutICount(i)._1
        val indizesWhereChunkPatternMatches: Seq[Int] = sentChunkedWithoutI.zipWithIndex.filter(_._1==chunkpattern).map(_._2)
        indizesWhereChunkPatternMatches.foreach(x=>println(sentFilteredSigns(x)))
        println("")
      }
    }
    */


    println("SENTENCES (AT MOST " + maxRedundantChunkPattern + " CHUNK PATTERN OCCURRENCES) : " + sentFinal.result().size)

    // - - - - - - - - - - -
    // We save the reviews in a file to run openIE in it
    // These sentences are saved in "sentFinal"
    // - - - - - - - - - - -

    new PrintWriter(s"data/${filename}_ssplit.txt") { // open new file
      sentFinal.result().foreach(x => write(x + "\n"))
      //sentences.foreach(x => write(x + "\n"))
      close // close file
    }

    // - - - - - - - - - - -
    // We calculate how many chunk pattern occur at least twice
    // - - - - - - - - - - -

    println("We calculate how many chunk pattern occur at least twice")
    var countOne: Double = 0
    var countMoreThanOne: Double = 0
    sentChunkedWithoutICount.foreach(x => {
      if (x._2 == 1) {
        countOne += 1
      }
      else if (x._2 > 1) {
        countMoreThanOne += 1
      }
    })
    println("countOne: " + countOne)
    println("countMoreThanOne: " + countMoreThanOne)
    println("So viel Prozent der Pos Kombinationen kommen mindestens doppelt vor: " + (countMoreThanOne / (countOne + countMoreThanOne)) * 100)

  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def parseExtractions(filename: String, ElasticIndexName: String) = {

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // open extractions from file and split them
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("loadFiles")
    val inputFile1: File = new File("data/" + filename) // read from file


    val extr: Vector[String] = Files.toString(inputFile1, Charset.forName("UTF-8")).split("\n\n").toVector

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // get sentences
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    val justSentences: Vector[String] = extr.map(x => x.split("\n")(0))
    println(s"We have ${justSentences.size} sentencens")

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // get chunks of the sentences
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("")
    println("chunks")

    var counter1 = 0
    val justChunks: Seq[Array[String]] = justSentences.map(sent => {
      if (counter1 % 1000 == 0) println("counter1: " + counter1 + " : " + justSentences.size)
      counter1 += 1
      val pos = getPOS(sent)
      val chunked: Array[String] = chunking(pos.map(x => x._2).toArray, pos.map(x => x._1).toArray)
      val chunkedReplacedO = chunked.zipWithIndex.map { case (chunk, i) => {
        if (chunk == "O") {
          pos(i)._1
        } else {
          chunk
        }
      }
      }
      chunkedReplacedO
    })

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // get a Vector with tokenized sentences and their extractions tokenized as well
    // some filtering
    // result = extractions
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("")
    println("get a Vector with tokenized sentences and their extractions tokenized as well")
    var counter2 = 0

    val extractionsPrecisions: Vector[List[String]] = extr
      .map(_.split("\n").toList) // split the lines in one extraction part
      .map(extrLines => {
      extrLines.map(line => {
        line.split(" ").take(1).mkString("")
      })
    })

    val extractions: Vector[List[List[String]]] = extr
      .map(_.split("\n")) // split the lines in one extraction part
      .map(_.toList)
      .map(extractionPart => extractionPart
        .map(line => {
          if (counter2 % 1000 == 0) println("counter2: " + counter2 + " : " + justSentences.size)
          counter2 += 1

          val indexList = line.indexOf("List([")

          def getlineCleaned(line:String):String = {
            if (indexList >= 0) {
              val indexColon = line.indexOf(":")
              line.drop(indexColon+1)
            } else {
              line
            }
          }

          val lineCleaned = getlineCleaned(line)


          val indexFirst = lineCleaned.indexOf("(")
          val indexLast = lineCleaned.lastIndexOf(")")
          if (indexFirst == -1 || indexLast == -1) {
            getPOS(lineCleaned).map(_._2).toList // when the line is the original sentence tokenize it
          } else {
            lineCleaned.drop(indexFirst + 1).dropRight(lineCleaned.size - indexLast).split(";").toList.map(x => x.trim)
              .filter(x => x != "") // filter triple  which are actually tuples //TODO Geht das wirklich gut?
          }
        })
        .filter(line => !line.mkString(" ").contains("List([")) // filter all Context Extractions of OIE5 since they do not deliver usefull extractions anyways
        //.filter(line => !line.mkString(" ").contains("T:"))// filter all time Extractions of OIE5
        //.filter(line => !line.mkString(" ").contains("L:"))// filter all location Extractions of OIE5
      )

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // We want to know which word in the extraction matches with which word in the sentence
    // result: rulesAll
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("")
    println(" We want to know which word in the extraction matches with which word in the sentence")
    var counter3 = 0
    val rulesAll = Vector.newBuilder[Vector[String]]
    val precisionsAll = Vector.newBuilder[Vector[String]]
    val sentencesAll = Vector.newBuilder[Int]
    // e.g. rulesAll.result():
    // Vector(Vector(, 1;2 3 4;6 8;)) // the inner Vector stands for the different rules for ONE sentence
    // for the sentence:
    // So this is really for accessories and big parts
    // 0,38 (this; is really for; and parts)
    val parsedExtractionsSize = extractions.size
    for (extractionIndex <- extractions.indices) {
      if (counter3 % 1000 == 0) println("counter3: " + counter3 + " : " + extractions.size)
      counter3 += 1
      //println("rulesAll: " + extractionIndex + " of " + parsedExtractionsSize)
      val rules = Vector.newBuilder[String]
      val precisions = Vector.newBuilder[String]

      for (lineNumberOfExtraction <- extractions(extractionIndex).indices) {
        var rule: String = ""

        if (lineNumberOfExtraction != 0) { // exclude the sentence itself (only extraction lines are considered)
          if (!(extractions(extractionIndex)(lineNumberOfExtraction)(0) == "No extractions found.")) { // ollie uses this line to indicate that there is no extracion
            if (extractions(extractionIndex)(lineNumberOfExtraction).size >= 2) {

              for (partOfTriple <- extractions(extractionIndex)(lineNumberOfExtraction)) {

                val partOfTripleCleaned = partOfTriple.split(" ").map(token => {
                  if (token.contains("T:") || token.contains("L:")) {
                    token.drop(2)
                  } else token
                }).mkString(" ")

                val partOfTripleToken: Vector[String] = getPOS(partOfTripleCleaned).map(_._2)

                partOfTripleToken.zipWithIndex.foreach { case (wordInExtracion, wordInExtracionIndex) => { // for every word in one triple part

                  var occurenceIndices: String = "wordNotInSentence"
                  var wordBeforeInSentence = ""
                  var wordAfterInSentence = ""
                  var wordBeforeInTriple = partOfTripleToken.lift(wordInExtracionIndex - 1) match {
                    case Some(x) => x;
                    case None => ""
                  }
                  var wordAfterInTriple = partOfTripleToken.lift(wordInExtracionIndex + 1) match {
                    case Some(x) => x;
                    case None => ""
                  }

                  extractions(extractionIndex)(0).zipWithIndex.foreach { case (wordInSentence, j) => if (wordInSentence == wordInExtracion) {
                    wordBeforeInSentence = extractions(extractionIndex)(0).lift(j - 1) match {
                      case Some(x) => x;
                      case None => ""
                    }
                    wordAfterInSentence = extractions(extractionIndex)(0).lift(j + 1) match {
                      case Some(x) => x;
                      case None => ""
                    }

                    if (occurenceIndices == "wordNotInSentence") {
                      if (wordBeforeInSentence == wordBeforeInTriple) {
                        occurenceIndices = j.toString

                      } else if (wordAfterInSentence == wordAfterInTriple) {
                        occurenceIndices = j.toString

                      } else {
                        occurenceIndices = j.toString
                      }
                    } else {
                      if (wordBeforeInSentence == wordBeforeInTriple) {
                        occurenceIndices = j.toString

                      } else if (wordAfterInSentence == wordAfterInTriple) {
                        occurenceIndices = j.toString

                      }
                    }

                  }
                  }


                  rule += occurenceIndices


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

        rules += rule
        precisions += extractionsPrecisions(extractionIndex)(lineNumberOfExtraction)

      }
      val rulesAndPrecisions: Vector[(String, String)] = rules.result().zip(precisions.result()).filterNot(_._1.isEmpty)
      rulesAll += rulesAndPrecisions.map(_._1)
      precisionsAll += rulesAndPrecisions.map(_._2)
      sentencesAll += extractionIndex
    }
    val rules: Vector[Vector[String]] = rulesAll.result()
    val precisions: Vector[Vector[String]] = precisionsAll.result()
    val sentences: Vector[Int] = sentencesAll.result()


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // We transform rulesAll: now, the extraction matches the CTS (chunk tag sequence)
    // delete the extraction where wordNotInSentence
    // LOGIC:
    // We take a Chunk pattern , say B-NP I-NP I-NP B-PP B-NP, and a extraction rule, say 0,1,2;3;4, and delete the I-* as from the beginning to the end of both lists
    // after one round it would look like: B-NP I-NP B-PP B-NP / 0,1;2;3 -> 1 has been deleted in the second list and the rest following count one down
    // after two rounds it would look like: B-NP B-PP B-NP / 0;1;2 -> 1 has been deleted in the second list and the rest following count one down
    // FINISH
    // result: chunksAndExtractions
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("")
    println(" We transform rulesAll: now, the extraction matches the CTS (chunk tag sequence)")

    val chunksAndExtractions = Vector.newBuilder[chunkAndExtractions]
    // e.g. "So this is really for accessories and big parts"
    // chunksAndExtractions: Vector(chunkAndExtractions(O$B-NP$B-VP$B-ADVP$B-PP$B-NP$O$B-NP,Vector(1, 2 3 4, 6 7),0))
    var misscreatedExtraction = 0
    var wordNotInSentenceCounter = 0
    var counter4 = 0
    var counterId = 0
    for (i <- 0 to rules.size - 1) {
      if (counter4 % 1000 == 0) println("counter4: " + counter4 + " : " + rules.size)
      counter4 += 1
      val rulesOfSentence: Vector[String] = rules(i)
      val precisionsOfSentence: Vector[String] = precisions(i)
      val chunksOfSentence: Vector[String] = justChunks(i).toVector // (B-NP, B-PP, B-NP)

      // this part a vector which contain a reduces token Indice
      // result like (0 1, 2, 3)

      val chunkedTagsReduced = Vector.newBuilder[String] // hier wird der reduzierte chunk Vector gesteichert
      chunkedTagsReduced += chunksOfSentence(0) // fügt den ersten chunk hinzu (eg. B-NP)
      val tokenIndices: Seq[String] = chunksOfSentence.indices.map(_.toString) // erstellt Liste mit den indices zu den chunks (0,1,2)
      val tokenIndicesReduced = Vector.newBuilder[String] // hier wird der reduzierte Indice Vector gespeichert
      var tokenIndicesReducedTMP: String = tokenIndices(0) // nimmt sich den ersten index
      for (i <- 1 until chunksOfSentence.size) {
        if (chunksOfSentence(i)(0).toString != "I") {
          chunkedTagsReduced += chunksOfSentence(i)
          tokenIndicesReduced += tokenIndicesReducedTMP
          tokenIndicesReducedTMP = ""
          tokenIndicesReducedTMP += " " + tokenIndices(i)
        } else {
          tokenIndicesReducedTMP += " " + tokenIndices(i)
        }
      }
      tokenIndicesReduced += tokenIndicesReducedTMP

      val tokenIndicesReducedResult = tokenIndicesReduced.result() // (0 1,  2,  3,  4,  5 6 7 8 9)

      val chunkedTagsReducedResult = chunkedTagsReduced.result() // (B-NP, B-VP, B-NP, B-PP, B-NP, .)


      for (j <- 0 to rulesOfSentence.size - 1) { // für jede extractions Regel

        val ruleOfSentence: String = rulesOfSentence(j)
        val precisionOfSentence: String = precisionsOfSentence(j)
        val ruleOfSentenceTriple: Vector[String] = ruleOfSentence.split(";").toVector
          .filter(_ != "") // filter parts of triple which are empty (usually the part at the and 0;1 3 4 5 6 7 8 9;2;)
        val containswordNotInSentence: Boolean = ruleOfSentence.contains("wordNotInSentence")
        if (ruleOfSentenceTriple.size >= 2 && !containswordNotInSentence) { // the triple must have at least 2 parts otherwise its not a valid extraction


          var alreadyUsedIndices = Vector.newBuilder[Int] // here we store indices which are already present in the pattern
          val ruleOfSentenceTripleNew = ruleOfSentenceTriple.map(triplePart => {
            val triplePartSplitted: Array[String] = triplePart.split(" ").filter(_ != "")
            var poristionIntokenIndicesReducedResult: Vector[Int] = (for (index <- triplePartSplitted) yield {
              val indexOfChunk = tokenIndicesReducedResult.map(chunkWithIndices => chunkWithIndices.contains(index)).indexOf(true)
              if (alreadyUsedIndices.result().contains(indexOfChunk)) {
                None
              } else {
                alreadyUsedIndices += indexOfChunk
                Some(indexOfChunk)
              }
            }).toVector.flatten
            poristionIntokenIndicesReducedResult.mkString(" ")
          })


          counterId += 1
          chunksAndExtractions += chunkAndExtractions(chunkedTagsReducedResult.mkString("$"), ruleOfSentenceTripleNew, precision = precisionOfSentence, sentencesIndex = justSentences(sentences(i)), id = counterId)

          //OLD
          /*
          var chunksOfSentenceUpdated: Vector[String] = chunksOfSentence

          var ruleOfSentenceTripleUpdated: Vector[String] = ruleOfSentenceTriple

          while (chunksOfSentenceUpdated.map(x => x(0).toString).contains("I")) {
            val chunksOfSentenceOnlyFirstChar: Vector[String] = chunksOfSentenceUpdated.map(x => x(0).toString)
            val indexOfI = chunksOfSentenceOnlyFirstChar.indexOf("I")
            chunksOfSentenceUpdated = chunksOfSentenceUpdated.take(indexOfI) ++ chunksOfSentenceUpdated.drop(indexOfI + 1)
            ruleOfSentenceTripleUpdated = ruleOfSentenceTripleUpdated.map(part => {
              val partSplitted = part.split(" ").filter(_ != "")
              var ruleOfSentenceTripleUpdatedPartAsVector: Vector[Int] = Vector[Int]()
              val firstIndice = partSplitted.head

              if (part.split(" ")(0) != "") {
                ruleOfSentenceTripleUpdatedPartAsVector = part.split(" ").map(_.toInt).toVector
              } else {
                // That happens in case another chunk eats the chunk which is located in this chunk e.g.:
                // I was constantly losing my debit card and drivers license
                // 0,22 (I; was losing my debit card and drivers license; T:constantly)
                // constantly belongs to the chunk of "was constantly losing" (VP)
              }

              // count the remaining indices down
              val ruleOfSentenceTripleUpdatedPartAsVectorUpdated: Vector[Int] = ruleOfSentenceTripleUpdatedPartAsVector.filter(x => {
                if (x == indexOfI) false else true
              }).map(x => {
                if (x >= indexOfI) x - 1 else x
              })

              // this part a vector which contain a reduces token Indice
              // result like (0 1, 2, 3)
              val replacementForEmtyPart: Option[Vector[String]] = if(ruleOfSentenceTripleUpdatedPartAsVectorUpdated.isEmpty){
                val replacementForEmtyPart: Vector[Option[Vector[String]]] = for(partIndices <- tokenIndicesReducedResult) yield {
                  val partIndicesAsVector = partIndices.trim.split("").toVector
                  val firstIndiceInPart = partIndicesAsVector.indexOf(firstIndice)
                  if (firstIndiceInPart >= 0 && partIndicesAsVector(0) != firstIndice.toString) {
                    Some(Vector(partIndicesAsVector(0)))
                  } else {
                    None
                  }
                }
                replacementForEmtyPart.flatten.headOption

              }else{None}


              println(replacementForEmtyPart)
              println(ruleOfSentenceTripleUpdatedPartAsVectorUpdated)

              replacementForEmtyPart match {
                case Some(newIndice) => newIndice.mkString("")
                case None => ruleOfSentenceTripleUpdatedPartAsVectorUpdated.mkString(" ")
              }
              ruleOfSentenceTripleUpdatedPartAsVectorUpdated.mkString(" ")
            })
          }

          if (!ruleOfSentenceTripleUpdated.contains("")) {


            /*if(chunksOfSentenceUpdated.mkString("$") == "B-NP$B-VP$B-NP$B-PP$B-NP"){
              //println(justChunks(i).mkString(" "))
              println(justSentences(i))
            }*/

            chunksAndExtractions += chunkAndExtractions(chunksOfSentenceUpdated.mkString("$"), ruleOfSentenceTripleUpdated,precision = precisionOfSentence)
          } else {
            println("")
            println("ERROR: misscreatedExtraction")
            println("justSentences(i): " + justSentences(i))
            println("extractions(i): " + extractions(i)(j + 1))
            println("ruleOfSentence: " + ruleOfSentence)
            println("ruleOfSentenceTripleUpdated: " + ruleOfSentenceTripleUpdated)
            println("chunksOfSentence: " + chunksOfSentence.zip(getPOS(justSentences(i)).map(_._2).toList))
            println("POS: " + getPOS(justSentences(i)))
            println("")
            misscreatedExtraction += 1
          }
          */
        } else {
          wordNotInSentenceCounter += 1
          /*println("")
          println("ERROR: wordNotInSentence")
          println("justSentences(i): " + justSentences(i))
          println("extractions(i): " + extractions(i)(j + 1))
          println("ruleOfSentence: " + ruleOfSentence)
          println("chunksOfSentence: " + chunksOfSentence.zip(getPOS(justSentences(i)).map(_._2).toList))
          println("")*/
        }
      }
    }

    println("")
    println("misscreatedExtraction: If a chunk in the extraction rules do not match any chunk in the sentence (usually due to chunks errors: NNs CC NNS(I can't fix  it))")
    // misscreatedExtraction:
    // That happens in case another chunk eats the chunk which is located in this chunk e.g.:
    // I was constantly losing my debit card and drivers license
    // 0,22 (I; was losing my debit card and drivers license; T:constantly)
    // constantly belongs to the chunk of "was constantly losing" (VP)
    println("misscreatedExtraction: " + misscreatedExtraction)
    println("wordNotInSentenceCounter: If a word in the extraction is not found in the sentence (usually commas I'LL FIX IT)")
    println("wordNotInSentenceCounter: " + wordNotInSentenceCounter)
    println("")

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // take care of duplicated extraction rules
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    println("take care of duplicated extraction rules")
    println("justSentences.size: " + justSentences.size)

    val chunksAndExtractionsResult: Vector[chunkAndExtractions] = chunksAndExtractions.result()
    println("chunksAndExtractions.result(): " + chunksAndExtractionsResult.size)
    /*var chunksAndExtractionsDisjunct = Vector[chunkAndExtractions]()
    var counter5 = 0
    for (i <- 0 until chunksAndExtractionsResult.size) {
      if(counter5 % 1000 == 0) println("counter5: " + counter5+ " : "+chunksAndExtractionsResult.size)
      counter5 += 1
      val chunksAndExtraction = chunkAndExtractions(chunksAndExtractionsResult(i).chunks, chunksAndExtractionsResult(i).extractionTriple, 1, chunksAndExtractionsResult(i).precision,i,justSentences(chunksAndExtractionsResult(i).sentencesIndex.toInt))

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
          val id = x.id
          val newCount: Int = chunksAndExtractionsDisjunct(chunkFoundIndex).count + 1
          val newPricsion: String = chunksAndExtractionsDisjunct(chunkFoundIndex).precision + " "+chunksAndExtraction.precision
          val newSentences = x.sentencesIndex + " SPLITHERE "+chunksAndExtraction.sentencesIndex
          chunksAndExtractionsDisjunct = chunksAndExtractionsDisjunct.updated(chunkFoundIndex, chunkAndExtractions(chunk, extractionTriple, newCount,newPricsion,id,newSentences))
        }
      }
    }*/
    indexExtractions(ElasticIndexName, chunksAndExtractionsResult)

  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // indexExtractions
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def indexExtractions(ElasticIndexName: String, chunksAndExtractions: Vector[chunkAndExtractions]): Unit = {

    println("")
    println("indexExtractions")
    println("establish connection to ES")
    val client = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
    client.execute {
      deleteIndex((ElasticIndexName))
    }
    client.execute {
      deleteIndex((ElasticIndexName + "_sentences"))
    }

    println("create Index")
    client.execute {
      createIndex((ElasticIndexName)) mappings (
        mapping("doc") as(
          keywordField("chunkPattern"),
          keywordField("precision"),
          keywordField("extraction"),
          intField("count"),
          intField("id")
        ))
    }
    client.execute {
      createIndex((ElasticIndexName + "_sentences")) mappings (
        mapping("doc") as(
          keywordField("sentences"),
          intField("id")
        ))
    }

    println("")
    println("index Extractions")
    var counter6 = 0
    chunksAndExtractions.foreach(x => {
      //for (i <- 0 to chunksAndExtractions.size - 1) {
      counter6 += 1
      if (counter6 % 1000 == 0) println("counter6: " + counter6 + " : " + chunksAndExtractions.size)

      val chunkAndExtraction = x

      client.execute {
        indexInto((ElasticIndexName) / "doc").fields(
          "pattern" -> chunkAndExtraction.chunks,
          "extractions" -> chunkAndExtraction.extractionTriple.mkString(","),
          "count" -> chunkAndExtraction.count,
          "precision" -> chunkAndExtraction.precision,
          "id" -> chunkAndExtraction.id
        )
      }
      client.execute {
        indexInto((ElasticIndexName + "_sentences") / "doc").fields(
          "id" -> chunkAndExtraction.id,
          "sentences" -> chunkAndExtraction.sentencesIndex
        )
      }
      Thread.sleep(4)
    })
    client.close()
  }

  def testExtraction: Unit = {
    while (true) {
      print("Sentence: ")
      val sentence: String = scala.io.StdIn.readLine()
      /*val sentences: Vector[String] = (for (i <- (0 to 1000)) yield sentence).toVector
      sentences.zipWithIndex.foreach(x => {
        println(x._2)
        val extracted = extract(x._1)

        for (i <- 0 until extracted._1.size) {
          val extraction = extracted._1(i)
          val precision = extracted._2(i)
          val sentID = extracted._3(i)

        }
      })*/
      val extracted: (Vector[Vector[String]], Vector[String], Vector[String]) = extract(sentence)
      for (i <- 0 until extracted._1.size) {
        val extraction: Vector[String] = extracted._1(i)
        val precision: String = extracted._2(i)
        val sentID: String = extracted._3(i)
        println("[id:" + sentID + "] " + "[p:" + precision + "] " + extraction)
      }
    }
  }

  def extract(sentence: String): (Vector[Vector[String]], Vector[String], Vector[String]) = {
    val extractionsBack = Vector.newBuilder[Vector[String]]
    val extractionsPrecisionBack = Vector.newBuilder[String]
    val extractionsSentIDBack = Vector.newBuilder[String]

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    //  get NER Annotation
    //  another advantage here is that the ner tagger gives us correct tokenization (difficult cases like Mar., -> Vektor(DATE,,))
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    //(sentenceAsVector, nerVectorResult, tokenVectorResult)
    val getner: (Vector[String], Vector[String], Vector[String]) = getNER(sentence)
    val sentenceSplitted: Vector[String] = getner._1.filterNot(x => x == "’s") // TODO: just filtering the "'s" might not be sufficient in every case e.g. when we want to show properties | POS tag of 's is "POS" | but nevertheless important for correct sentence chunking see "Bsp 1.:"
    val tokenVector = getner._3
    val nerVector = getner._2


    if (sentenceSplitted.size > 0) {
      var extractionFinished = 0
      //println("sentenceSplitted.size: "+sentenceSplitted.size)
      var dropRight = 0
      while (extractionFinished == 0 && sentenceSplitted.dropRight(dropRight).size > 3) { // TODO make this more efficient
        getExtractions(sentenceSplitted.dropRight(dropRight), pipelineNER)
        extractionFinished = extractionsBack.result().size
        dropRight += 1
      }
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // divide the sentence in sentence parts (splitted by commas)
    // - - - - - - - - - - - - - - - - - - - - - - - - -
    /*
        val sentenceSplittedComma: Vector[String] = sentenceSplitted.mkString(" ").split(",").map(_.trim).toVector

        // - - - - - - - - - - - - - - - - - - - - - - - - -
        // make combinations of that sentence parts and send each to the extraction function
        // - - - - - - - - - - - - - - - - - - - - - - - - -

        if (sentenceSplittedComma.size > 1) {
          //if (false) {
          val sentenceCombinations = sentenceSplittedComma.toSet[String].subsets().map(_.toVector).toVector // get all combination of sentence-parts
          //println("sentenceCombinations: "+sentenceCombinations)
          for (combi <- sentenceCombinations) {
            if (combi.size == 2) { // process only part-senetces in tuples or alone
              //println("combi: "+combi)
              val sentence = combi.mkString(" , ") // mk a sent out of the combination
              println("\n")
              println("sentenceCombi: " + sentence)
              val sentenceSplitted: Vector[String] = sentence.split(" ").toVector
              //println ("sentenceChunked: "+sentenceChunked)
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
              println("\n")
              println("sentenceCombi: " + sentence2)
              val sentenceSplitted2: Vector[String] = sentence2.split(" ").toVector
              //println ("sentenceChunked: "+sentenceChunked)
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
              //println("combi: "+combi)
              val sentence = combi.mkString(" , ") // mk a sent out of the combination
              println("\n")
              println("sentenceCombi: " + sentence)
              var sentenceSplitted: Vector[String] = sentence.split(" ").toVector
              //println ("sentenceChunked: "+sentenceChunked)


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
            //println("sentenceSplitted.size: "+sentenceSplitted.size)
            var dropRight = 0
            while (extractionFinished == 0 && sentenceSplitted.dropRight(dropRight).size > 3) { // TODO make this more efficient
              getExtractions(sentenceSplitted.dropRight(dropRight), pipelineNER)
              extractionFinished = extractionsBack.result().size
              dropRight += 1
            }
          }
          //getExtractions(Vector(sentence))
        }
        */


    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // takes one sentence and produce extractions
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    def getExtractions(sentenceSplitted: Vector[String], pipelineNER: StanfordCoreNLP): Unit = {
      implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method


      val pos: Vector[(String, String)] = getPOS(sentenceSplitted.mkString(" "))
      val chunked: Array[String] = chunking(pos.map(x => x._2).toArray, pos.map(x => x._1).toArray)
      val chunkedReplacedO: Array[String] = chunked.zipWithIndex.map { case (chunk, i) => {
        if (chunk == "O") {
          pos(i)._1
        } else {
          chunk
        }
      }
      }

      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // bring chunks and chunk annotations in a shortened form
      // - - - - - - - - - - - - - - - - - - - - - - - - -


      val chunkedTagsReduced = Vector.newBuilder[String]
      val chunkedSentenceReduced = Vector.newBuilder[String]


      chunkedTagsReduced += chunkedReplacedO(0)
      var chunkedSentenceReducedTMP: String = sentenceSplitted(0)

      for (i <- 1 until chunkedReplacedO.size) {
        if (chunkedReplacedO(i)(0).toString != "I") {
          chunkedTagsReduced += chunkedReplacedO(i)
          chunkedSentenceReduced += chunkedSentenceReducedTMP
          chunkedSentenceReducedTMP = ""
          chunkedSentenceReducedTMP += " " + sentenceSplitted(i)
        } else {
          chunkedSentenceReducedTMP += " " + sentenceSplitted(i)
        }
      }


      chunkedSentenceReduced += chunkedSentenceReducedTMP
      val chunkedTagsReducedResult: Vector[String] = chunkedTagsReduced.result()
      if (chunkedTagsReducedResult.size <= 2) return extractionsBack.result()
      var chunkedSentenceReducedResult: Vector[String] = chunkedSentenceReduced.result().map(_.trim)
      //println("chunkedSentenceReducedResult: " + chunkedSentenceReducedResult)
      //println("chunkedTagsReducedResult: " + chunkedTagsReducedResult)


      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // replace the NER tags in the sentence with the original words
      // - - - - - - - - - - - - - - - - - - - - - - - - -

      val chunkedSentenceReducedResultXY = chunkedSentenceReducedResult.map(chunk => {
        //println("word: "+chunk)
        var chunkSplitted = chunk.split(" ").toVector
        for (i <- 0 until nerVector.size) {
          val ner = nerVector(i)
          val index = chunkSplitted.indexOf(ner)
          //println("ner: "+ner)
          //println("index: "+index)
          if (index >= 0) {
            chunkSplitted = chunkSplitted.updated(index, tokenVector(i))
            //println("new: "+chunkSplitted)
            //println("splittedChunk: "+splittedChunk)
          }
        }
        chunkSplitted.mkString(" ")
      })
      //println("chunkedSentenceReducedResultXY: "+chunkedSentenceReducedResultXY)
      //println("sentenceChunked: "+sentenceChunked.zip(chunkedTags))
      //println("chunkedTags: "+chunkedTags)
      //println("chunkedTagsReducedResult: "+chunkedTagsReducedResult.mkString(" "))
      //println("chunkedSentenceReducedResult: "+chunkedSentenceReducedResult)


      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // qery elasticsearch for extraction rules (ordered by extraction quality)
      // - - - - - - - - - - - - - - - - - - - - - - - - -


      val extractions = Vector.newBuilder[String]
      val extractionsPrecision = Vector.newBuilder[String]
      val extractionsSentID = Vector.newBuilder[String]

      /*
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
      */


      val iterator2: Iterator[SearchHit] = SearchIterator.hits(client, search("amazon_extractions_4") sortByFieldDesc ("count") query termQuery("pattern.keyword", chunkedTagsReducedResult.mkString("$")) keepAlive (keepAlive = "10m") size 1000) //sourceInclude List("text.string","id"))
      iterator2.foreach(searchhit => {
        extractions += searchhit.sourceField("extractions").toString
        extractionsPrecision += searchhit.sourceField("precision").toString
        extractionsSentID += searchhit.sourceField("id").toString
      })


      val extractionsPresicionResult = extractionsPrecision.result()
      val extractionsSentIDResult = extractionsSentID.result()




      // Martin is the little brother of Carla
      //Martin is with Julia
      // For this reason I bought the watch

      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // print extraction in dependency to the quality (count) of them
      // - - - - - - - - - - - - - - - - - - - - - - - - -

      val extractionsAsSet = extractions.result().toSet.toArray
      //println("extractions.result(): "+extractions.result().zip(extractionsCount.result()))
      if (extractionsAsSet.size > 0) { // is there a result
      case class extractionClass(part1: Option[Vector[Int]], part2: Option[Vector[Int]], part3: Option[Vector[Int]])
        //for (i <- 0 until (if (extractions.result().size > 1) 2 else extractions.result().size)) { // give maximum 2 extractions
        for (i <- 0 until extractionsAsSet.size) { // give maximum 2 extractions
          //print("[" + extractionsCountResult(i) + "] ")
          //if(extractionsCount.result()(i)>20){ // is the count big enough
          val temp: Array[String] = extractionsAsSet(i).split(",").filter(!_.isEmpty)
          //.map(x=>x.split(" ").toVector).toVector

          val parts: Array[Vector[Int]] = temp.map(part => {
            part.split(" ").map(_.toInt).toVector
          })

          //val extraction: extractionClass = extractionClass(temp.lift(0).split(" ").map(_.toInt).toVector, temp(1).split(" ").map(_.toInt).toVector, temp(2).split(" ").map(_.toInt).toVector)
          //println("max 3: "+extraction.part3.max+" size: "+(chunkedTagsReduced.size-1))
          /*if (extraction.part1.max > chunkedSentenceReducedResultXY.size - 1 || extraction.part2.max > chunkedSentenceReducedResultXY.size - 1 || extraction.part3.max > chunkedSentenceReducedResult.size - 1) {
            println("index out") // TODO where does it happen that sometimes the extraction index is higher than the length of the pattern
          } else {*/

          val extractionsBackTMP = Vector.newBuilder[String]
          parts.foreach(part => {
            var tmp = ""
            part.foreach(x => {
              tmp += chunkedSentenceReducedResultXY(x) + " "
            })
            extractionsBackTMP += tmp.trim
          })


          extractionsPrecisionBack += extractionsPresicionResult(i)
          extractionsSentIDBack += extractionsSentIDResult(i)
          val extractionsBackTMPResult: Vector[String] = extractionsBackTMP.result()
          extractionsBack += extractionsBackTMPResult
          //}
        }
      }
    }

    Tuple3(extractionsBack.result(), extractionsPrecisionBack.result(), extractionsSentIDBack.result())
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

    //println("sentenceAsVector: " + sentenceAsVector)
    //println("indexInOriginalSentenceResult: " + indexInOriginalSentenceResult)

    (sentenceAsVector, nerVectorResult, tokenVectorResult)
    // Beispiel:
    // sentenceAsVector: (Vector(PERSON1, PERSON2, is, black, .)
    // nerVectorResult: Vector(PERSON1, PERSON2)
    // tokenVectorResult: Vector(Barack, Obama)
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


}
