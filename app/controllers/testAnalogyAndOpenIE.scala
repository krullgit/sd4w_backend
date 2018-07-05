package controllers

import java.io.{FileInputStream, InputStream}
import java.util.Properties

import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl.{fuzzyQuery, search}
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.{SearchHit, SearchIterator}
import controllers.analogyHelpers.readAnalogies
import de.tu_berlin.dima.code.getExtractions
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import javax.inject.Inject
import org.apache.spark.mllib.linalg.DenseMatrix
import play.api.libs.json._
import play.api.mvc._
import opennlp.tools.chunker.{ChunkerME, ChunkerModel}
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.{SearchHit, SearchIterator}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.io.Source
import scala.util.control.NonFatal


object testAnalogyAndOpenIE {

  // lsa1 = kleines trainingsset
  // lsa2 = großes trainingsset (30 mio token) -> 300 svm dim
  // lsa3 = großes trainingsset (30 mio token) -> 600 svm dim
  // lsa4 = großes trainingsset (30 mio token) -> 50 svm dim
  // lsa5 = großes trainingsset (30 mio token) -> 1200 svm dim

  val numberSentencesWeWantToProcess = 500

  var resultThresholdAdd = 0.8
  val lsaName = "lsa5.txt"
  val wordListName = "wordListRows2.txt" // lsa1 needs wordListRows1

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get lsa data
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("READ lsa")
  val lsa = scala.Vector.newBuilder[scala.Vector[Double]]
  val lsaFile = Source.fromFile("data/" + lsaName).getLines.toVector
  for (i <- 0 until lsaFile.length) {
    lsa += (lsaFile(i).split(" ").map(_.toDouble).toVector)
  }
  println("lsa: " + lsa.result().size)
  val wordListRows: Vector[String] = Source.fromFile("data/" + wordListName).getLines().toVector(0).split(",").toVector
  println("wordListRows: " + wordListRows.size)
  println("READY lsa")

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load rest
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  val client: HttpClient = HttpClient(ElasticsearchClientUri("localhost", 9200)) // new client
  implicit val timeout = Duration(20, "seconds") // is the timeout for the SearchIterator.hits method

  var modelIn: InputStream = null;
  var model: ChunkerModel = null;
  try {
    modelIn = new FileInputStream("data/en-chunker.bin")
  }
  model = new ChunkerModel(modelIn)
  val chunker: ChunkerME = new ChunkerME(model)

  println("LOADING STANFORD PARSER")
  println("pipelinePos")
  val propsPos: Properties = new Properties() // set properties for annotator
  propsPos.put("annotators", "tokenize, ssplit,pos") // set properties
  propsPos.put("pos.model", "data/english-left3words-distsim.tagger")
  val pipelinePos: StanfordCoreNLP = new StanfordCoreNLP(propsPos) // annotate file

  println("pipelineNER")
  val propsNER: Properties = new Properties() // set properties for annotator
  propsNER.put("annotators", "tokenize, ssplit, pos, lemma, ner, regexner")
  propsNER.put("regexner.mapping", "data/jg-regexner.txt")
  propsNER.put("pos.model", "data/english-left3words-distsim.tagger")
  val pipelineNER: StanfordCoreNLP = new StanfordCoreNLP(propsNER) // annotate file

  println("pipelineSplit")
  val propsSplit: Properties = new Properties()
  propsSplit.put("annotators", "tokenize, ssplit")
  propsSplit.put("pos.model", "data/english-left3words-distsim.tagger")
  val pipelineSplit: StanfordCoreNLP = new StanfordCoreNLP(propsSplit)
  println("READY LOADING STANFORD PARSER")
  /*
    println("LOADING STANFORD PARSER 4")
    val propsDep: Properties = new Properties()
    propsDep.put("annotators", "tokenize,ssplit,pos,depparse")
    propsDep.put("pos.model", "data/english-left3words-distsim.tagger")
    val pipelineDep: StanfordCoreNLP = new StanfordCoreNLP(propsDep)
    println("READY LOADING STANFORD PARSER")
  */




  while (true) {
    try {
      val extractionObject = new getExtractions(client, pipelinePos, pipelineNER, pipelineSplit, pipelineSplit, model)

      //var doc1: Vector[String] = Vector("Siemens", "buy", "-1")
      println("")
      println("search input request (this form: ''subject,relation,object'' or 'any'): ")
      var resultThreshold = 0.0


      var doc1: Vector[String] = scala.io.StdIn.readLine().split(",").toVector
      var doc1InWordListBuilder = Array.newBuilder[Boolean]
      val extraObjDoc1left = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)
      val extraObjDoc1middle = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)

      val extraObjDoc1right = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)

      def calcDoc1(triplePart: String, newAnaloyExtraction: analogyExtr_lsaVersion) {
        var vectorDoc1: DenseMatrix = newAnaloyExtraction.accumulatedDocumentVector(triplePart)
        var lengthFirstWordVector: Double = newAnaloyExtraction.lengthOfVector(vectorDoc1)
        newAnaloyExtraction.vectorDoc1 = vectorDoc1
        newAnaloyExtraction.lengthFirstWordVector = lengthFirstWordVector
      }

      val Doc1leftInWortList = doc1(0).split(" ").map(x=>wordListRows.indexOf(x)).max
      val Doc1leftInWortmiddle = doc1(1).split(" ").map(x=>wordListRows.indexOf(x)).max
      val Doc1leftInWortright = doc1(2).split(" ").map(x=>wordListRows.indexOf(x)).max



      if (doc1(0) != "any" || Doc1leftInWortList<0) {

        if(doc1(0) == "any") {
          doc1InWordListBuilder += true
        }else if(Doc1leftInWortList>=0){
          calcDoc1(doc1(0), extraObjDoc1left)
          doc1InWordListBuilder += true
          resultThreshold = resultThreshold + resultThresholdAdd
        }else{
          doc1InWordListBuilder += false
          resultThreshold = resultThreshold + resultThresholdAdd
        }
      }
      if (doc1(1) != "any" || Doc1leftInWortmiddle<0) {
        if(doc1(1) == "any") {
          doc1InWordListBuilder += true
        }else if (Doc1leftInWortmiddle>=0) {
          calcDoc1(doc1(1), extraObjDoc1middle)
          doc1InWordListBuilder += true
          resultThreshold = resultThreshold + resultThresholdAdd
        }else{
          doc1InWordListBuilder += false
          resultThreshold = resultThreshold + resultThresholdAdd
        }


      }
      if (doc1(2) != "any" || Doc1leftInWortright<0) {
        if(doc1(2) == "any") {
          doc1InWordListBuilder += true
        }else if(Doc1leftInWortright>=0) {
          calcDoc1(doc1(2), extraObjDoc1right)
          doc1InWordListBuilder += true
          resultThreshold = resultThreshold + resultThresholdAdd
        }else{
          doc1InWordListBuilder += false
          resultThreshold = resultThreshold + resultThresholdAdd
        }
      }

      val doc1InWordList = doc1InWordListBuilder.result()

      //val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("test").matchAllQuery.keepAlive(keepAlive = "1m").size(50).sourceInclude("text.string")) // returns 50 values and blocks until the iterator gets to the last element

      val elasticQuery = doc1.filter(!_.equals("any")).mkString(" ") // filter the 'anys' for the query
      val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("test") query matchQuery("posLemmas", elasticQuery) keepAlive (keepAlive = "10m") size (100) sourceInclude ("text.string"))


      var sentenceCounter = 0
      var exceptionCounter = 0
      var extractionCounter = 0
      iterator.takeWhile(searchhit => sentenceCounter <= numberSentencesWeWantToProcess).foreach(searchHit => { // for each element in the iterator
        val text = searchHit.sourceField("text").asInstanceOf[Map[String, String]]("string")
        val sentences: Vector[String] = extractionObject.ssplit(text).flatMap(x => x.split("\n"))

        val extractions: Vector[(Vector[Vector[String]], Vector[String], Vector[String])] = sentences.par.map(sentence => {
          sentenceCounter += 1
          try {
            Some(extractionObject.extract(sentence))
          } catch {
            case NonFatal(ex) => {
              exceptionCounter += 1
              println("exceptionCounter: " + exceptionCounter)
              None
            }
          }

        }).flatten.toVector

        extractions.foreach(extracted => {
            extractionCounter += 1
            /*if (sentenceCounter % 10 == 0) {
              println("Sentences processed: " + sentenceCounter)
            }*/
            //println("sentence: "+sentence)

            extracted._1.zipWithIndex.foreach(x => {
              extractionCounter += 1
              val index = x._2
              val extraction: Vector[String] = extracted._1(index)
              val precision = extracted._2(index)
              val sentID = extracted._3(index)
              var distances: Vector[Double] = extraction.zipWithIndex.map(x => {
                val index = x._2
                val extractionPart: String = x._1
                if (index < doc1.size) {
                  if(!doc1InWordList(index)){
                    if(extractionPart.toLowerCase.contains(doc1(index).toLowerCase())){
                      1.0
                    }else{
                      0.0
                    }
                  } else if (doc1(index) != "any") {
                    extraObjDoc1left.calcDistanceAPI(extractionPart)
                  } else {
                    0.0
                  }
                } else {
                  0.0
                }
              }).map(x =>
                BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
              )
              if (distances.sum >= resultThreshold) {
                printExtraction(extraction, precision, sentID, distances)

                def printExtraction(extraction: Vector[String], precision: String, sentID: String, distances: Vector[Double]) = {
                  val simsum = BigDecimal(distances.sum).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble
                  println("[id:" + sentID + "] " + "[p:" + precision + "] " + "[SimSum:" + simsum + "] " + "[Sim:" + distances.mkString(",") + "] " + "[" + extraction.mkString(";") + "] ")
                }
              }
            })
        })
      }
      ) // end iterator foreach
      println("")
      println("Sentences processed: " + sentenceCounter)
      println("Extractions made: " + extractionCounter)
    }
    catch {
      case NonFatal(ex) => {
        ex.printStackTrace()
        println("this do not work")
      }
    }
  } // end while


  def main(args: Array[String]): Unit = {

  }
}