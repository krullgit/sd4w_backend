package controllers

import java.util.Properties

import edu.stanford.nlp.pipeline.StanfordCoreNLP
import org.apache.spark.mllib.linalg.DenseMatrix

import scala.io.Source


object testAnalogy {
  // lsa1 = kleines trainingsset
  // lsa2 = großes trainingsset (30 mio token) -> 300 svm dim
  // lsa3 = großes trainingsset (30 mio token) -> 600 svm dim
  // lsa4 = großes trainingsset (30 mio token) -> 50 svm dim
  // lsa5 = großes trainingsset (30 mio token) -> 1200 svm dim

  val lsaName = "lsa5.txt"
  val wordListName = "wordListRows2.txt" // lsa1 needs wordListRows1

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // get lsa data
  // - - - - - - - - - - - - - - - - - - - - - - - - -

  println("READ lsa")
  val lsa = scala.Vector.newBuilder[scala.Vector[Double]]
  val lsaFile = Source.fromFile("data/"+lsaName).getLines.toVector
  for (i <- 0 until lsaFile.length) {
    lsa += (lsaFile(i).split(" ").map(_.toDouble).toVector)
  }
  println("lsa: " + lsa.result().size)
  val wordListRows: Vector[String] = Source.fromFile("data/"+wordListName).getLines().toVector(0).split(",").toVector
  println("wordListRows: " + wordListRows.size)
  println("READY lsa")

  // - - - - - - - - - - - - - - - - - - - - - - - - -
  // load rest
  // - - - - - - - - - - - - - - - - - - - - - - - - -


  println("LOADING STANFORD PARSER")
  /*println("1")
  val propsPos: Properties = new Properties() // set properties for annotator
  propsPos.put("annotators", "tokenize, ssplit,pos") // set properties
  val pipelinePos: StanfordCoreNLP = new StanfordCoreNLP(propsPos) // annotate file*/

  println("2")
  val propsNER: Properties = new Properties() // set properties for annotator
  propsNER.put("annotators", "tokenize, ssplit, pos, lemma, ner, regexner")
  propsNER.put("regexner.mapping", "data/jg-regexner.txt")
  val pipelineNER: StanfordCoreNLP = new StanfordCoreNLP(propsNER) // annotate file

  /*println("3")
  val propsSplit: Properties = new Properties()
  propsSplit.put("annotators", "tokenize, ssplit")
  val pipelineSplit: StanfordCoreNLP = new StanfordCoreNLP(propsSplit)
  println("READY LOADING STANFORD PARSER")*/


  val extraObj = new analogyExtr_lsaVersion(lsa.result(), wordListRows, pipelineNER, pipelineNER, new DenseMatrix(1, 1, Array(1)), 0)

  def calcDoc1(triplePart: String, newAnaloyExtraction: analogyExtr_lsaVersion) {
    var vectorDoc1: DenseMatrix = newAnaloyExtraction.accumulatedDocumentVector(triplePart)
    var lengthFirstWordVector: Double = newAnaloyExtraction.lengthOfVector(vectorDoc1)
    newAnaloyExtraction.vectorDoc1 = vectorDoc1
    newAnaloyExtraction.lengthFirstWordVector = lengthFirstWordVector
  }

  while (true) {
    val twoWords: Vector[String] = scala.io.StdIn.readLine().split(",").toVector
    if (twoWords.size == 2) {
      calcDoc1(twoWords(0), extraObj)
      var distance = 0.0
      try {
        distance = extraObj.calcDistanceAPI(twoWords(1))
      } catch {
        case e: Exception => println("no result")
      }
      println("[" + distance + "]")
    } else if (twoWords.size == 1) {
      try {
        calcDoc1(twoWords(0), extraObj)
        var distances = Vector.newBuilder[Double]
        for (i <- 0 until wordListRows.size) {
          if (i % 1000 == 0) println(i)

            distances += extraObj.calcDistanceAPI(wordListRows(i))


        }
        val distancesSorted = distances.result().sorted.reverse.filterNot(_.toString.equals("NaN"))
        val indexOfMinDistance1 = distances.result().indexOf(distancesSorted(1))
        val indexOfMinDistance2 = distances.result().indexOf(distancesSorted(2))
        val indexOfMinDistance3 = distances.result().indexOf(distancesSorted(3))
        val indexOfMinDistance4 = distances.result().indexOf(distancesSorted(4))
        val indexOfMinDistance5 = distances.result().indexOf(distancesSorted(5))
        val indexOfMinDistance6 = distances.result().indexOf(distancesSorted(6))
        val indexOfMinDistance7 = distances.result().indexOf(distancesSorted(7))
        val indexOfMinDistance8 = distances.result().indexOf(distancesSorted(8))
        println("[" + distancesSorted(1) + "] " + wordListRows(indexOfMinDistance1))
        println("[" + distancesSorted(2) + "] " + wordListRows(indexOfMinDistance2))
        println("[" + distancesSorted(3) + "] " + wordListRows(indexOfMinDistance3))
        println("[" + distancesSorted(4) + "] " + wordListRows(indexOfMinDistance4))
        println("[" + distancesSorted(4) + "] " + wordListRows(indexOfMinDistance5))
        println("[" + distancesSorted(4) + "] " + wordListRows(indexOfMinDistance6))
        println("[" + distancesSorted(4) + "] " + wordListRows(indexOfMinDistance7))
        println("[" + distancesSorted(4) + "] " + wordListRows(indexOfMinDistance8))
      } catch {
        case e: Exception => println("no result")
      }
    }
  }



def main (args: Array[String] ): Unit = {

}
}