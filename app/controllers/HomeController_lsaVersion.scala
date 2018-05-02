package controllers

import java.util.Properties

import com.brkyvz.spark.linalg.MatrixLike
import controllers.analogyHelpers.readAvro
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import javax.inject.Inject
import org.apache.spark.mllib.linalg.DenseMatrix
import play.api.libs.json._
import play.api.mvc._
import controllers.analogyHelpers.readAnalogies

import scala.io.Source

/**
  * A very small controller that renders a home page.
  */
class HomeController_lsaVersion @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get lsa data
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("READ lsa")
  val lsa = scala.Vector.newBuilder[scala.Vector[Double]]
  val lsaFile = Source.fromFile("data/lsa.txt").getLines.toVector
  for (i <-  0 until lsaFile.length ) {
    lsa+=(lsaFile(i).split(" ").map(_.toDouble).toVector)
  }

  val wordListRows: Vector[String] = Source.fromFile("data/wordListRowsAndCols.txt").getLines().toVector(0).split(",").toVector
  println("READY lsa")

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load analogies
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("READ ANALOGIES")
  val analogies: Map[String, Vector[(String, String)]] = readAnalogies()
  println("READY ANALOGIES")

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load the stanford annotator for NER tagging and lemmatisation
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("LOADING STANFORD PARSER")
  val props: Properties = new Properties() // set properties for annotator
  props.put("annotators", "tokenize, ssplit, pos, lemma, ner, regexner")
  props.put("regexner.mapping", "data/jg-regexner.txt")
  val pipelineNER: StanfordCoreNLP = new StanfordCoreNLP(props) // annotate file

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load the stanford annotator for ssplit
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  val propsSplit: Properties = new Properties()
  propsSplit.put("annotators", "tokenize, ssplit")
  val pipelineSplit: StanfordCoreNLP = new StanfordCoreNLP(propsSplit)
  println("READY LOADING STANFORD PARSER")

  var doc1:String = "Car crash in New York."

  val newAnaloyExtraction = new analogyExtr_lsaVersion(lsa.result(), wordListRows,pipelineNER,pipelineSplit,new DenseMatrix(1,1,Array(1)),0)

  def calcDoc1(){
    var vectorDoc1: DenseMatrix = newAnaloyExtraction.accumulatedDocumentVector(doc1)
    var lengthFirstWordVector: Double = newAnaloyExtraction.lengthOfVector(vectorDoc1)
    newAnaloyExtraction.vectorDoc1 = vectorDoc1
    newAnaloyExtraction.lengthFirstWordVector = lengthFirstWordVector
  }
  calcDoc1()



  /*
    def index(doc1:String,doc2:String) = Action { implicit request =>
      Ok(Json.toJson(analogyExtraction.calcDistanceAPI(doc2)))
    }
  */
  def save = Action { request =>
    val doc1Potential:String = request.body.asJson.get("doc1").toString().drop(1).dropRight(1)
    println(doc1Potential)
    if(!doc1.equals(doc1Potential)){
      println("doc1 will change")
      var doc1PotentialPlusAnalogies: String = doc1Potential
      val NERsOfDoc1: Array[String] = newAnaloyExtraction.getNER(doc1Potential).split(" ")
      NERsOfDoc1.foreach(x=>{
        analogies.get(x) match{
          case Some(foundAnalogy) => doc1PotentialPlusAnalogies += " "+ foundAnalogy(0)._1
          case None => println("No analogy found")
        }
      })
      println("doc1PotentialPlusAnalogies + NERsOfDoc1: "+doc1PotentialPlusAnalogies)
      doc1 = doc1PotentialPlusAnalogies
      calcDoc1()
    }
    val doc2:String = request.body.asJson.get("doc2").toString()

    Ok(Json.toJson(newAnaloyExtraction.calcDistanceAPI(doc2))).as("text/html; charset=utf-8");
  }
  def getNER = Action { request =>

    val sentence:String = request.body.asJson.get("sentence").toString()

    Ok(Json.toJson(newAnaloyExtraction.getNER(sentence))).as("text/html; charset=utf-8");
  }
  def getAnalogies = Action { request =>

    val sentence:String = request.body.asJson.get("sentence").toString()
    println("execute Analogy request")


    val analogiesFound = Vector.newBuilder[(String, String)]

    val NERsOfSentence: Array[String] = newAnaloyExtraction.getNER(sentence).split(" ")
    NERsOfSentence.foreach(x=>{
      analogies.get(x) match{
        case Some(foundAnalogy) => analogiesFound += Tuple2(x,foundAnalogy(0)._1)
        case None => println("No analogy found")
      }
    })

    Ok(Json.toJson(analogiesFound.result().map(x=>x._2))).as("text/html; charset=utf-8");
  }
}