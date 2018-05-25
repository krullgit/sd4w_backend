package controllers

import java.io.{FileInputStream, InputStream}
import java.util.Properties

import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl.search
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.SearchIterator
import controllers.analogyHelpers.readAnalogies
import de.tu_berlin.dima.code.getExtractions
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import javax.inject.Inject
import org.apache.spark.mllib.linalg.DenseMatrix
import play.api.libs.json._
import play.api.mvc._
import opennlp.tools.chunker.{ChunkerME, ChunkerModel}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.io.Source


object testAnalogyAndOpenIE {

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get lsa data
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("READ lsa")
  val lsa = scala.Vector.newBuilder[scala.Vector[Double]]
  val lsaFile = Source.fromFile("data/lsa.txt").getLines.toVector
  for (i <- 0 until lsaFile.length) {
    lsa += (lsaFile(i).split(" ").map(_.toDouble).toVector)
  }
  println("lsa: "+lsa.result().size)
  val wordListRows: Vector[String] = Source.fromFile("data/wordListRows.txt").getLines().toVector(0).split(",").toVector
  println("wordListRows: "+wordListRows.size)
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
  println("1")
  val propsPos: Properties = new Properties() // set properties for annotator
  propsPos.put("annotators", "tokenize, ssplit,pos") // set properties
  val pipelinePos: StanfordCoreNLP = new StanfordCoreNLP(propsPos) // annotate file

  println("2")
  val propsNER: Properties = new Properties() // set properties for annotator
  propsNER.put("annotators", "tokenize, ssplit, pos, lemma, ner, regexner")
  propsNER.put("regexner.mapping", "data/jg-regexner.txt")
  val pipelineNER: StanfordCoreNLP = new StanfordCoreNLP(propsNER) // annotate file

  println("3")
  val propsSplit: Properties = new Properties()
  propsSplit.put("annotators", "tokenize, ssplit")
  val pipelineSplit: StanfordCoreNLP = new StanfordCoreNLP(propsSplit)
  println("READY LOADING STANFORD PARSER")





  var doc1: Vector[String] = Vector("Siemens", "buy", "-1")

  val extraObjDoc1left = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)
  val extraObjDoc1middle = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)
  val extraObjDoc1right = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineSplit, new DenseMatrix(1, 1, Array(1)), 0)

  def calcDoc1(triplePart: String, newAnaloyExtraction: analogyExtr_lsaVersion) {
    var vectorDoc1: DenseMatrix = newAnaloyExtraction.accumulatedDocumentVector(triplePart)
    var lengthFirstWordVector: Double = newAnaloyExtraction.lengthOfVector(vectorDoc1)
    newAnaloyExtraction.vectorDoc1 = vectorDoc1
    newAnaloyExtraction.lengthFirstWordVector = lengthFirstWordVector
  }

  if(doc1(0)!="-1"){
    calcDoc1(doc1(0), extraObjDoc1left)
  }
  if(doc1(1)!="-1"){
    calcDoc1(doc1(1), extraObjDoc1middle)
  }
  if(doc1(2)!="-1"){
    calcDoc1(doc1(2), extraObjDoc1right)
  }

  val extractionObject = new getExtractions(client,chunker,pipelinePos,pipelineNER,pipelineSplit)

  val iterator = SearchIterator.hits(client, search("test").matchAllQuery.keepAlive(keepAlive = "1m").size(50).sourceInclude("text.string")) // returns 50 values and blocks until the iterator gets to the last element


  var sentenceCounter=0

  iterator.foreach(searchHit => { // for each element in the iterator
    val text = searchHit.sourceField("text").asInstanceOf[Map[String,String]]("string")
    val sentences: Vector[String] = extractionObject.ssplit(text).flatMap(x=>x.split("\n"))
    sentences.foreach(sentence => {
      sentenceCounter += 1
      if(sentenceCounter%100==0){
        println("Sentences processed: "+ sentenceCounter)
      }
      //println("sentence: "+sentence)
      val extracted = Vector.newBuilder[(Vector[(String, String, String)], Vector[Int])]
      try {
        extracted += extractionObject.extract(sentence)
      } catch {
        case e: Exception =>
      }

      val extractedResult: Vector[(Vector[(String, String, String)], Vector[Int])] = extracted.result().filterNot(x=>x._1.isEmpty)
      if(extractedResult.size>0){
        val extractedResultInner = extractedResult(0)
        val countMax = extractedResultInner._2.max
        val countMaxIndex = extractedResultInner._2.indexOf(countMax)
        val extraction: (String, String, String) = extractedResultInner._1(countMaxIndex)
        var distance1 = 0.0
        var distance2 = 0.0
        var distance3 = 0.0
        if(doc1(0)!="-1"){
          try{
            distance1 = extraObjDoc1left.calcDistanceAPI(extraction._1)
          } catch {
            case e: Exception =>e.printStackTrace
          }
        }
        if(doc1(1)!="-1"){
          try{
            distance2 = extraObjDoc1middle.calcDistanceAPI(extraction._2)
          } catch {
            case e: Exception =>e.printStackTrace
          }
        }
        if(doc1(2)!="-1"){
          try{
            distance3 = extraObjDoc1right.calcDistanceAPI(extraction._3)
          } catch {
            case e: Exception =>e.printStackTrace
          }
        }
        if(distance1>0.7 || distance2>0.7||distance3>0.7){
          println("["+countMax+"] "+extraction)
          println("["+distance1+"] "+"["+distance2+"] "+"["+distance3+"]")
          println("")
        }
      }
    })
  })


    def main(args: Array[String]): Unit = {

    }
  }