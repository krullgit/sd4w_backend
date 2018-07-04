package de.tu_berlin.dima.code


import java.io.{File, PrintWriter}
import java.nio.charset.Charset

import com.google.common.io.Files
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.{SearchHit, SearchIterator}
import edu.stanford.nlp.ling.CoreAnnotations.{SentencesAnnotation, _}
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.util.CoreMap
import opennlp.tools.chunker.ChunkerME

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.duration.Duration
import edu.stanford.nlp.tagger.maxent.MaxentTagger


class getExtractions(client: HttpClient, chunker: ChunkerME, pipelinePos: StanfordCoreNLP, pipelineNER: StanfordCoreNLP, pipelineSplit: StanfordCoreNLP, pipelineDep: StanfordCoreNLP) {

  implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method
  case class chunkAndExtractions(chunks: String = "", extractionTriple: Vector[String] = Vector(""), count: Int = 0)

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


  def chunking(sent: Array[String], pos: Array[String]): Array[String] = {
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
      if(i%10 ==0){
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


  def estimateRecall(filename: String, sentences: Vector[String],maxRedundantChunkPattern: Int, wordsLowerBorder:Int, wordsUpperBound:Int) = {

    // - - - - - - - - - - -
    // We filter all sentences which do contain one of the following characters
    // - - - - - - - - - - -

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
      }).map(_+".")
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
      }) // get POS tags



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
      .map { case (vectorOfTuples, i) => {
        if (i % 10000 == 0) println("chunked: " + i)
        chunking(vectorOfTuples.map(x => x._2).toArray, vectorOfTuples.map(x => x._1).toArray)
      }
      }

    // - - - - - - - - - - -
    // I group the chunk peaces e.g. "big green house"
    // - - - - - - - - - - -

    val sentChunkedWithoutI: Seq[String] = sentChunked.map(x => x.filter(x => x(0).toString != "I").mkString(" ")).map(_.mkString(" ")) // I filter the I because I want to reduce every chun to one toke e.g. "big green house"
    /*new PrintWriter(s"data/${filename}_chunks.txt") { // open new file
      sentChunkedWithoutI.foreach(x => write(x + "\n"))
      close // close file
    }*/

    // - - - - - - - - - - -
    // We count the chunks patterns which occurred in the reviews
    // - - - - - - - - - - -

    val sentChunkedWithoutICount: Vector[(String, Int)] = sentChunkedWithoutI // make List of POS tags to String
      .groupBy(chunk => chunk) // groupby POS Tag strings
      .mapValues(_.size) // count occurrences
      .toVector

    // - - - - - - - - - - -
    // We just want to retain "maxRedundantChunkPattern" of those sentences, which chunk patterns are the same.
    // These sentences are saved in "sentFinal"
    // - - - - - - - - - - -

    val chunkCountChunk: Seq[String] = sentChunkedWithoutICount.map(x => x._1)
    var chunkCountCountFinished: immutable.Seq[Int] = Vector.fill(chunkCountChunk.size)(0)
    val sentFinal = Vector.newBuilder[String]
    sentChunkedWithoutI.zipWithIndex.foreach(sentAndIndex => {
      val sentChunks: String =  sentAndIndex._1
      val index: Int = sentAndIndex._2
      val sent: String =  sentFilteredSigns(index)
      val positionOfChunk: Int = chunkCountChunk.indexOf(sentChunks)
      if(positionOfChunk >= 0){
        val countFinshed = chunkCountCountFinished(positionOfChunk)
        if(countFinshed < maxRedundantChunkPattern){
          sentFinal += sent
          chunkCountCountFinished = chunkCountCountFinished.updated(positionOfChunk,countFinshed+1)
        }else{
        }
      }else{
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


    println("SENTENCES (AT MOST "+maxRedundantChunkPattern+" CHUNK PATTERN OCCURRENCES) : "+sentFinal.result().size)

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

  def parseExtractions(filename: String) = {


    println("loadFiles")
    val inputFile1: File = new File("data/" + filename) // read from file
    val extr: Vector[String] = Files.toString(inputFile1, Charset.forName("UTF-8")).split("\n\n").toVector
    val justSentences = extr.map(x => x.split("\n")(0))


    var counter1 = 0
    val justChunks: Seq[Array[String]] = extr.map(sentAndExtr => {
      println("counter1: " + counter1)
      counter1 += 1
      val splitted = sentAndExtr.split("\n")(0)
      val pos = getPOS(splitted)
      val chunked: Array[String] = chunking(pos.map(x => x._2).toArray, pos.map(x => x._1).toArray)
      chunked
    })

    println("parsedExtractions")
    var counter2 = 0
    val extractions: Vector[List[List[String]]] = extr
      .map(_.split("\n")) // split the lines in one extraction part
      .map(_.toList)
      .map(extractionPart => extractionPart
        .map(line => {
          println("counter2: " + counter2)
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

    println("rulesAll")
    var counter3 = 0

    val rulesAll = Vector.newBuilder[Vector[String]]
    val parsedExtractionsSize = extractions.size
    for (extractionIndex <- extractions.indices) {
      println("counter3: " + counter3)
      counter3 += 1
      //println("rulesAll: " + extractionIndex + " of " + parsedExtractionsSize)
      val rules = Vector.newBuilder[String]

      //rules += (for (i <- parsedExtractions(extracionCounter)) yield {
      //  i._1
      //}).mkString(" ")
      //println(parsedExtractions(i)(0)(0)) // print sentences

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


              //if (extractions(extractionIndex)(0).mkString(" ").contains("period")){println(extractions(extractionIndex)(0).mkString(" "))}
              if (!tmpbool) {
                //if (extractions(extractionIndex)(0).mkString(" ").contains("period")){println(extractions(extractionIndex)(0).mkString(" "))}
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
                      println(extractions(extractionIndex)(0).mkString(" "))
                      println(extractions(extractionIndex)(lineNumberOfExtraction))
                      println(rule)
                      println(wordInExtracionCleaned)
                    }

                    //rule += "#" + wordInExtracion // mark that this word accours more than one time (for later processing) TODO: fix that
                    if (wordInExtracionIndex >= partOfTriple.split(" ").size - 1) {
                      rule += ";"
                    } else {
                      //println("ERROR1")
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
    //rules.zip(extrChunked).foreach( x=>{println(x._1.mkString(" "));println(x._2.mkString(" ")+"\n")})


    val chunksAndExtractions = Vector.newBuilder[chunkAndExtractions]
    var chunksAndExtractionMissmatch = 0
    var misscreatedExtraction = 0

    println("rules.size: " + rules.size)
    println("chunksOfSentence.size: " + justChunks.size)


    var counter4 = 0

    //for(i <- 0 to 20){
    for (i <- 0 to rules.size - 1) {
      println("counter4: " + counter4)
      counter4 += 1
      //println("\n")
      //println(extractions(i)(0).zipWithIndex)
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
            //println("indexOfI: "+indexOfI)
            //println("chunksOfSentenceUpdated: "+chunksOfSentenceUpdated)
            chunksOfSentenceUpdated = chunksOfSentenceUpdated.take(indexOfI) ++ chunksOfSentenceUpdated.drop(indexOfI + 1)
            //println("chunksOfSentenceUpdated: "+chunksOfSentenceUpdated)
            //println("ruleOfSentenceTripleUpdated: "+ruleOfSentenceTripleUpdated)
            ruleOfSentenceTripleUpdated = ruleOfSentenceTripleUpdated.map(part => {
              //println("ruleOfSentenceTripleUpdated Before: "+part.split(" ").toVector)
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

              //println("ruleOfSentenceTripleUpdatedPartAsVectorUpdated: "+ruleOfSentenceTripleUpdatedPartAsVectorUpdated)

              ruleOfSentenceTripleUpdatedPartAsVectorUpdated.mkString(" ")
            })
          }
          //println("ruleOfSentenceTripleUpdated: "+ruleOfSentenceTripleUpdated)
          if (!ruleOfSentenceTripleUpdated.contains("")) {


            /*if(chunksOfSentenceUpdated.mkString("$") == "B-NP$B-VP$B-NP$B-PP$B-NP"){
              //println(justChunks(i).mkString(" "))
              println(justSentences(i))
            }*/

            chunksAndExtractions += chunkAndExtractions(chunksOfSentenceUpdated.mkString("$"), ruleOfSentenceTripleUpdated)
          } else {
            misscreatedExtraction += 1
          }
        }
      }
    }

    println("chunksAndExtractionMissmatch: " + chunksAndExtractionMissmatch)
    println("misscreatedExtraction: " + misscreatedExtraction)

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
      println("counter5: " + counter5)
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
    println("createIndex FINISHED")
    var counter6 = 0

    for (i <- 0 to chunksAndExtractions.size - 1) {
      println("counter6: " + counter6)
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
      for (i <- 0 until extracted._1.size) {
        val extraction = extracted._1(i)
        val count = extracted._2(i)
        println("[" + count + "] " + extraction)
      }


    }
  }


  def extract(sentence: String): (Vector[(String, String, String)], Vector[Int]) = {
    val extractionsBack = Vector.newBuilder[(String, String, String)]
    val extractionsCountBack = Vector.newBuilder[Int]

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    //  get NER Annotation
    //  another advantage here is that the ner tagger gives us correct tokenization (difficult cases like Mar., -> Vektor(DATE,,))
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    //(sentenceAsVector, nerVectorResult, tokenVectorResult)
    val getner = getNER(sentence)
    val sentenceSplitted: Vector[String] = getner._1.filterNot(x => x == "." || x == "?" || x == "!" || x == "'s" || x == "’s") // TODO: just filtering the "'s" might not be sufficient in every case e.g. when we want to show properties | POS tag of 's is "POS" | but nevertheless important for correct sentence chunking see "Bsp 1.:"
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



    // - - - - - - - - - - - - - - - - - - - - - - - - -
    // takes one sentence and produce extractions
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    def getExtractions(sentenceSplitted: Vector[String], pipelineNER: StanfordCoreNLP): Unit = {
      implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method

      // TODO: make this more efficient
      //println(sentenceSplitted.size)
      //if(sentenceSplitted(0) == "who" || sentenceSplitted(0) == "Who" || sentenceSplitted(0) == "Why"|| sentenceSplitted(0) == "why"|| sentenceSplitted(0) == "how"|| sentenceSplitted(0) == "How"|| sentenceSplitted(0) == "What"|| sentenceSplitted(0) == "what"|| sentenceSplitted(0) == "Where"|| sentenceSplitted(0) == "where"|| sentenceSplitted(0) == "When"|| sentenceSplitted(0) == "when"|| sentenceSplitted(0) == "Which"|| sentenceSplitted(0) == "which"){
      //    return -2
      //}

      var sentenceSplittedWithoutComma = Vector[String]()

      // - - - - - - - - - - - - - - - - - - - - - - - - -
      // apply the sentence chunker
      // - - - - - - - - - - - - - - - - - - - - - - - - -

      //println("sentenceSplitted: "+sentenceSplitted)
      //println("sentence: "+sentence)
      //val sentenceChunked: Vector[String] = sentence.split(" ").toVector

      //println("sentenceSplitted: "+sentenceSplitted)

      val pos: Vector[(String, String)] = getPOS(sentenceSplitted.mkString(" "))
      //println(pos)
      var chunkedTags: Vector[String] = chunking(pos.map(x => x._2).toArray, pos.map(x => x._1).toArray).toVector
      //println("chunkedTags: "+chunkedTags)
      //println(chunkedTags)
      // I filter the comma after the chunks are correctly built
      // This might be not very efficient since we have to filter the sentence and the chunk list as well
      // TODO: make this more efficient
      val positionOfComma = sentenceSplitted.indexOf(",")
      if (positionOfComma >= 0) {
        //println("comma found")
        chunkedTags = chunkedTags.zipWithIndex.filter(_._2 != positionOfComma).map(_._1)
        sentenceSplittedWithoutComma = sentenceSplitted.zipWithIndex.filter(_._2 != positionOfComma).map(_._1)
      } else {
        sentenceSplittedWithoutComma = sentenceSplitted
      }
      //println("chunkedTags: "+chunkedTags)
      //println("sentenceSplittedWithoutComma: "+sentenceSplittedWithoutComma)


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
      println("chunkedSentenceReducedResult: " + chunkedSentenceReducedResult)
      println("chunkedTagsReducedResult: " + chunkedTagsReducedResult)


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

      //println("extractions.result(): "+extractions.result().zip(extractionsCount.result()))
      if (extractions.result().size > 0) { // is there a result
      case class extractionClass(part1: Vector[Int], part2: Vector[Int], part3: Vector[Int])
        for (i <- 0 until (if (extractions.result().size > 1) 2 else extractions.result().size)) { // give maximum 2 extractions
          //for(i <- 0 until extractions.result().size){ // give maximum 2 extractions
          //print("[" + extractionsCountResult(i) + "] ")
          //if(extractionsCount.result()(i)>20){ // is the count big enough
          val temp = extractions.result()(i).split(",")
          //.map(x=>x.split(" ").toVector).toVector
          val extraction: extractionClass = extractionClass(temp(0).split(" ").map(_.toInt).toVector, temp(1).split(" ").map(_.toInt).toVector, temp(2).split(" ").map(_.toInt).toVector)
          //println("max 3: "+extraction.part3.max+" size: "+(chunkedTagsReduced.size-1))
          if (extraction.part1.max > chunkedSentenceReducedResultXY.size - 1 || extraction.part2.max > chunkedSentenceReducedResultXY.size - 1 || extraction.part3.max > chunkedSentenceReducedResult.size - 1) {
            println("index out") // TODO where does it happen that sometimes the extraction index is higher than the length of the pattern
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
            extractionsBack += Tuple3(extractionsBackTMPResult(0), extractionsBackTMPResult(1), extractionsBackTMPResult(2))
          }
        }
      }
    }

    Tuple2(extractionsBack.result(), extractionsCountBack.result())
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

    println("sentenceAsVector: " + sentenceAsVector)
    //println("indexInOriginalSentenceResult: " + indexInOriginalSentenceResult)

    (sentenceAsVector, nerVectorResult, tokenVectorResult)
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  //
  // - - - - - - - - - - - - - - - - - - - - - - - - -


}
