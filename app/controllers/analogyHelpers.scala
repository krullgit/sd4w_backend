
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

object analogyHelpers {
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


  def readAnalogies(): Map[String, Vector[(String, String)]] = {
    val analogyBuilder = Map.newBuilder[String, Vector[(String,String)]]
    val source = scala.io.Source.fromFile("data/cosOfAngleMatrix.txt")
    val lines: Iterator[String] = source.getLines()
    while(lines.hasNext){
      val next = lines.next()
      val firstComma = next.indexOf(",")
      val mainToken = next.substring(1,firstComma)
      val rest = next.substring(firstComma+9,next.size-2)
      val tuplesAsString: Array[String] = rest.split(",")
      val tuplesAsList: Array[Array[String]] = tuplesAsString.map(x=>x.split("->").map(x=>x.trim))
      try {
        val tuplesAsTuples = tuplesAsList.map(x=>(x(0),x(1))).toVector
        analogyBuilder += (mainToken->tuplesAsTuples)
      }catch{
        case x:ArrayIndexOutOfBoundsException => println("can't parse analogies for: "+mainToken)
      }
    }
    //analogyBuilder.result().foreach(println(_))
    source.close()
    analogyBuilder.result()
  }

  def main(args: Array[String]): Unit = {
    readAnalogies()
  }
}
