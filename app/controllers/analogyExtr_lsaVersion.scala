
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
import org.apache.spark.mllib.linalg.DenseMatrix

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.concurrent.duration._
import com.brkyvz.spark.linalg.{LazyMatrix, MatrixLike, _}


class analogyExtr_lsaVersion(lsa: Vector[Vector[Double]], wordListRows: Vector[String], pipelineNER: StanfordCoreNLP, pipelineSplit: StanfordCoreNLP, var vectorDoc1: DenseMatrix, var lengthFirstWordVector: Double) {


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

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get NER representation of a sentence
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def getNER(sentence: String): String = { // get POS tags per sentence
    val document: Annotation = new Annotation(sentence)
    pipelineNER.annotate(document) // annotate
    val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList
    val a: Seq[(String, String)] = (for {
      sentence: CoreMap <- sentences
      token: CoreLabel <- sentence.get(classOf[TokensAnnotation]).asScala.toList
      _: String = token.get(classOf[TextAnnotation])
      _: String = token.get(classOf[PartOfSpeechAnnotation])
      lemma: String = token.get(classOf[LemmaAnnotation])
      regexner: String = token.get(classOf[NamedEntityTagAnnotation])

    } yield (lemma, regexner))
    val aa = a
      // make this : "(New,location) (York,location) (is,0) (great,0) (.,0)" to this: "New_York is great"
      .reduceLeft((tupleFirst, tupleSecond) => {
      if (tupleFirst._1.equals("``") || tupleFirst._1.equals("")) {
        (tupleSecond._1, tupleSecond._2)
      } else if (tupleFirst._2 == tupleSecond._2 && tupleSecond._2 != "O") {
        (tupleFirst._1 + "_" + tupleSecond._1, tupleSecond._2)
      } else {
        (" " + tupleFirst._1 + " " + tupleSecond._1, tupleSecond._2)
      }
    })._1
    // TODO WrappedArray(, , , , , , , this, be, a, test, as, well, ., '') there are to many whitespaces
    aa.toLowerCase().filter(!_.equals(""))
  }


  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get the accumulated vector
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def accumulatedDocumentVector(doc: String): DenseMatrix = {

    val token: Seq[String] = getNER(doc) // get the NER and lemma
      .split(" ").map(_.toLowerCase()).filter(!_.equals(""))
    val vectors = Vector.newBuilder[Vector[Double]]
    for (i <- 0 until token.size) {
      val tokenEntry = token(i)

      val index = wordListRows.indexOf(tokenEntry)
      if (index >= 0) {
        val lsaEntry = lsa(index)
        vectors += lsaEntry
      } else {
        println("no vector found to: " + tokenEntry)
      }
    }
    val vectorsResult: Seq[Vector[Double]] = vectors.result()
    //val vectorSum: MatrixLike = vectorsResult.reduce(_ + _).compute()
    if (vectorsResult.size > 0) {// check if there is at least one vector which we can sum up
      val vectorsSum: Vector[Double] = vectorsResult.reduce((x, y) => x.zip(y).map { case (x, y) => x + y })
      new DenseMatrix(1, vectorsSum.length, vectorsSum.toArray)
    } else { // if not numcols = 2 indicates that
      new DenseMatrix(1, 2, Array(1, 1))
    }

  }


  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get length of this vector
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def lengthOfVector(vector: DenseMatrix): Double = {
    (math.sqrt((vector * vector.transpose).compute().toString.toDouble))
  }

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // sentence splitter
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  def ssplit(text: String): Seq[String] = {
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

  def calcDistanceAPI(doc2: String): scala.collection.mutable.ArrayBuffer[Tuple3[Seq[Tuple2[String, Double]], Double,String]] = {
    val borderForCosAngle: Double = 0.0 // not important atm
    var cosOfAngleFirstWordSecondWord: Double = 0
    val returnList = scala.collection.mutable.ArrayBuffer[Tuple3[Seq[Tuple2[String, Double]], Double,String]]()

    // - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - -

    val sentences = ssplit(doc2).toVector

    sentences.foreach(doc2 => {
      val vectorDoc2: DenseMatrix = accumulatedDocumentVector(doc2)
      if (vectorDoc2.numCols != 2) {

        def calcDist(vector: DenseMatrix): Double = {
          val lengthSecondWordVector = lengthOfVector(vector)
          val x = ((vectorDoc1 * vectorDoc2.transpose).compute())
          (x / (lengthFirstWordVector * lengthSecondWordVector)).compute().toString.toDouble
        }

        val cos = calcDist(vectorDoc2)
        //List[Tuple2[String,Double]]
        val tokenAndDouble = mutable.LinkedHashMap[String, Double]()

        val tokenList: Seq[String] = getNER(doc2) // get the NER and lemma
          .split(" ").map(_.toLowerCase()).filter(!_.equals(""))

        for (token <- tokenList) {
          val tokenVector: DenseMatrix = accumulatedDocumentVector(token)
          if (tokenVector.numCols != 2) {
            val tokenLength = lengthOfVector(tokenVector)
            val x = ((vectorDoc1 * tokenVector.transpose).compute())
            val xxxx: Double = (x / (lengthFirstWordVector * tokenLength)).compute().toString.toDouble
            println("token: "+token+" similarity: "+xxxx)
            tokenAndDouble.put(token, xxxx)
          } else {
            println("token: "+token+" similarity: 0")
            tokenAndDouble.put(token, 0)
          }
        }

        returnList.append(Tuple3(tokenAndDouble.toList, cos,doc2))

      }
    })
    print(returnList)
    returnList
  }


}
