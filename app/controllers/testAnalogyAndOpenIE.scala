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

  val sentencesWeWantToProcess = 500
  var resultThreshold = 0.0
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




  while (true) {
    try {
      val extractionObject = new getExtractions(client, chunker, pipelinePos, pipelineNER, pipelineSplit,pipelineSplit)

      //var doc1: Vector[String] = Vector("Siemens", "buy", "-1")
      println("")
      println("search input request (this form: ''subject,relation,object'' or 'any'): ")

      var doc1: Vector[String] = scala.io.StdIn.readLine().split(",").toVector
      val extraObjDoc1left = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)
      val extraObjDoc1middle = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)

      val extraObjDoc1right = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)

      def calcDoc1(triplePart: String, newAnaloyExtraction: analogyExtr_lsaVersion) {
        var vectorDoc1: DenseMatrix = newAnaloyExtraction.accumulatedDocumentVector(triplePart)
        var lengthFirstWordVector: Double = newAnaloyExtraction.lengthOfVector(vectorDoc1)
        newAnaloyExtraction.vectorDoc1 = vectorDoc1
        newAnaloyExtraction.lengthFirstWordVector = lengthFirstWordVector
      }
      if (doc1(0) != "any") {
        resultThreshold = resultThreshold + resultThresholdAdd
        calcDoc1(doc1(0), extraObjDoc1left)
      }
      if (doc1(1) != "any") {
        resultThreshold = resultThreshold + resultThresholdAdd
        calcDoc1(doc1(1), extraObjDoc1middle)
      }
      if (doc1(2) != "any") {
        resultThreshold = resultThreshold + resultThresholdAdd
        calcDoc1(doc1(2), extraObjDoc1right)
      }


      //val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("test").matchAllQuery.keepAlive(keepAlive = "1m").size(50).sourceInclude("text.string")) // returns 50 values and blocks until the iterator gets to the last element

      val elasticQuery = doc1.filter(!_.equals("any")).mkString(" ") // filter the 'anys' for the query
      val iterator: Iterator[SearchHit] = SearchIterator.hits(client, search("test") query matchQuery("posLemmas", elasticQuery) keepAlive (keepAlive = "10m") size (100) sourceInclude ("text.string"))


      var sentenceCounter = 0
      iterator.takeWhile(searchhit => sentenceCounter <= sentencesWeWantToProcess).foreach(searchHit => { // for each element in the iterator
        val text = searchHit.sourceField("text").asInstanceOf[Map[String, String]]("string")
        val sentences: Vector[String] = extractionObject.ssplit(text).flatMap(x => x.split("\n"))
        sentences.foreach(sentence => {
          sentenceCounter += 1
          if (sentenceCounter % 10 == 0) {
            println("Sentences processed: " + sentenceCounter)
          }
          //println("sentence: "+sentence)
          val extracted = Vector.newBuilder[(Vector[(String, String, String)], Vector[Int])]
          extracted += extractionObject.extract(sentence)


          val extractedResult: Vector[(Vector[(String, String, String)], Vector[Int])] = extracted.result().filterNot(x => x._1.isEmpty)
          if (extractedResult.size > 0) {
            val extractedResultInner = extractedResult(0)
            val countMax = extractedResultInner._2.max
            val countMaxIndex = extractedResultInner._2.indexOf(countMax)
            val extraction: (String, String, String) = extractedResultInner._1(countMaxIndex)
            var distance1 = 0.0
            var distance2 = 0.0
            var distance3 = 0.0
            if (doc1(0) != "any") {
              distance1 = extraObjDoc1left.calcDistanceAPI(extraction._1)
            }
            if (doc1(1) != "any") {
              distance2 = extraObjDoc1middle.calcDistanceAPI(extraction._2)
            }
            if (doc1(2) != "any") {
              distance3 = extraObjDoc1right.calcDistanceAPI(extraction._3)
            }
            var distanceCount = distance1 + distance2 + distance3
            if (distanceCount >= resultThreshold) {
              println("[" + countMax + "] " + extraction)
              println("[" + distance1 + "] " + "[" + distance2 + "] " + "[" + distance3 + "]")
              println("")
            }
          }
        }
        )
      }) // end iterator foreach
    }
    catch {
      case NonFatal(ex) => {
        println("this do not work")
      }
    }
  } // end while


  def main(args: Array[String]): Unit = {

  }
}