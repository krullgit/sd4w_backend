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
import play.api.libs.json._
import shapeless.PolyDefns.->

import scala.util.{Failure, Success, Try}
import javax.inject.Inject

import controllers.analogyHelpers.readAvro
import play.api.mvc._

/**
  * A very small controller that renders a home page.
  */
class HomeController_avroVersion @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get coOccurrences from avro file (takes a while)
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("READ AVRO")
  val coOccurrences: Map[String, Map[String, Int]] = readAvro()
  println("READY READ AVRO")

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load the stanford annotator for NER tagging and lemmatisation
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("LOADING STANFORD PARSER NER")
  val props: Properties = new Properties() // set properties for annotator
  props.put("annotators", "tokenize, ssplit, pos, lemma, ner, regexner")
  props.put("regexner.mapping", "data/jg-regexner.txt")
  val pipelineNER: StanfordCoreNLP = new StanfordCoreNLP(props) // annotate file
  println("READY LOADING STANFORD PARSER NER")

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load the stanford annotator for ssplit
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("LOADING STANFORD PARSER Split")
  val propsSplit: Properties = new Properties()
  propsSplit.put("annotators", "tokenize, ssplit")
  val pipelineSplit: StanfordCoreNLP = new StanfordCoreNLP(propsSplit)
  println("READY LOADING STANFORD PARSER Split")

  var doc1:String = "Car crash in New York."

  val newAnaloyExtraction = new analogyExtr_avroVersion(coOccurrences,pipelineNER,pipelineSplit,Map[String, Int](),0)

  def calcDoc1(){
    var vectorDoc1: Map[String, Int] = newAnaloyExtraction.accumulatedDocumentVector(doc1)._1
    var lengthFirstWordVector: Double = newAnaloyExtraction.lengthOfVector(vectorDoc1)
    newAnaloyExtraction.vectorDoc1 = newAnaloyExtraction.accumulatedDocumentVector(doc1)._1
    newAnaloyExtraction.lengthFirstWordVector = newAnaloyExtraction.lengthOfVector(vectorDoc1)
  }
  calcDoc1()



  /*
    def index(doc1:String,doc2:String) = Action { implicit request =>
      Ok(Json.toJson(analogyExtraction.calcDistanceAPI(doc2)))
    }
  */
  def save = Action { request =>
    val doc1Potential:String = request.body.asJson.get("doc1").toString()
    println(doc1Potential)
    if(!doc1.equals(doc1Potential)){
      println("doc1 will change")
      doc1 = request.body.asJson.get("doc1").toString()
      calcDoc1()
    }
    val doc2:String = request.body.asJson.get("doc2").toString()

    Ok(Json.toJson(newAnaloyExtraction.calcDistanceAPI(doc2))).as("text/html; charset=utf-8");
  }
  def getNER = Action { request =>

    val sentence:String = request.body.asJson.get("sentence").toString()

    Ok(Json.toJson(newAnaloyExtraction.getNER(sentence))).as("text/html; charset=utf-8");
  }
}