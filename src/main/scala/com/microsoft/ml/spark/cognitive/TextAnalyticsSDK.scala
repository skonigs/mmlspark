package com.microsoft.ml.spark.cognitive

import com.azure.ai.textanalytics.models.{SentenceSentiment, SentimentConfidenceScores, TextAnalyticsRequestOptions}
import com.azure.ai.textanalytics.{TextAnalyticsClient, TextAnalyticsClientBuilder}
import com.azure.core.credential.AzureKeyCredential
import com.microsoft.ml.spark.core.contracts.{HasConfidenceScoreCol, HasInputCol}
import com.microsoft.ml.spark.core.schema.SparkBindings
import com.microsoft.ml.spark.io.http.HasErrorCol
import com.microsoft.ml.spark.logging.BasicLogging
import org.apache.http.client.methods.HttpRequestBase
import org.apache.spark.injections.UDFUtils
import org.apache.spark.ml.param.{Param, ParamMap, ServiceParam}
import org.apache.spark.ml.util.Identifiable._
import org.apache.spark.ml.{ComplexParamsReadable, ComplexParamsWritable, Transformer}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{DataTypes, StructType}
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import com.azure.ai.textanalytics.models

import java.net.URI
import scala.collection.JavaConverters._

abstract class TextAnalyticsSDKBase[T](val textAnalyticsOptions: Option[TextAnalyticsRequestOptions] = None)
  extends Transformer
  with HasInputCol with HasErrorCol
  with HasEndpoint with HasSubscriptionKey
  with ComplexParamsWritable with BasicLogging {

  protected val invokeTextAnalytics: String => TAResponseV4[T]

  protected def outputSchema: StructType

  protected lazy val textAnalyticsClient: TextAnalyticsClient =
    new TextAnalyticsClientBuilder()
      .credential(new AzureKeyCredential(getSubscriptionKey))
      .endpoint(getEndpoint)
      .buildClient()

  override def transform(dataset: Dataset[_]): DataFrame = {
    logTransform[DataFrame]({
      val invokeTextAnalyticsUdf = UDFUtils.oldUdf(invokeTextAnalytics, outputSchema)
      val inputColNames = dataset.columns.mkString(",")
      dataset.withColumn("Out", invokeTextAnalyticsUdf(col($(inputCol))))
        .select(inputColNames, "Out.result.*", "Out.error.*", "Out.statistics.*", "Out.*")
        .drop("result", "error", "statistics")
    })
  }

  override def transformSchema(schema: StructType): StructType = {
    // Validate input schema
    val inputType = schema($(inputCol)).dataType
    require(inputType.equals(DataTypes.StringType), s"The input column must be of type String, but got $inputType")

    // Making sure input schema doesn't overlap with output schema
    val fieldsIntersection = schema.map(sf => sf.name.toLowerCase)
      .intersect(outputSchema.map(sf => sf.name.toLowerCase()))
    require(fieldsIntersection.isEmpty, s"Input schema overlaps with transformer output schema. " +
      s"Rename the following input columns: [${fieldsIntersection.mkString(", ")}]")

    // Creating output schema (input schema + output schema)
    val consolidatedSchema = (schema ++ outputSchema).toSet
    StructType(consolidatedSchema.toSeq)
  }

  override def copy(extra: ParamMap): Transformer = defaultCopy(extra)
}

object TextAnalyticsLanguageDetection extends ComplexParamsReadable[TextAnalyticsLanguageDetection]

class TextAnalyticsLanguageDetection(override val textAnalyticsOptions: Option[TextAnalyticsRequestOptions] = None,
                                     override val uid: String = randomUID("TextAnalyticsLanguageDetection"))
  extends TextAnalyticsSDKBase[DetectedLanguageV4](textAnalyticsOptions)
  with HasConfidenceScoreCol {
  logClass()

  /**
   * Params for optional input column names.
   */
  // Country Hint
  final val countryHintCol: Param[String] = new Param[String](this, "countryHintCol", "country hint column name")

  final def getCountryHintCol: String = $(countryHintCol)

  final def setCountryHintCol(value: String): this.type = set(countryHintCol, value)
  setDefault(countryHintCol -> "CountryHint")

  override def outputSchema: StructType = DetectLanguageResponseV4.schema

  override protected val invokeTextAnalytics: String => TAResponseV4[DetectedLanguageV4] = (text: String) =>
    {
      val detectLanguageResultCollection = textAnalyticsClient.detectLanguageBatch(
        Seq(text).asJava, null, textAnalyticsOptions.orNull)
      val detectLanguageResult = detectLanguageResultCollection.asScala.head

      val languageResult = if (detectLanguageResult.isError) {
        None
      } else {
        Some(DetectedLanguageV4(
          detectLanguageResult.getPrimaryLanguage.getName,
          detectLanguageResult.getPrimaryLanguage.getIso6391Name,
          detectLanguageResult.getPrimaryLanguage.getConfidenceScore))
      }

      val error = if (detectLanguageResult.isError) {
        val error = detectLanguageResult.getError
        Some(TAErrorV4(error.getErrorCode.toString, error.getMessage, error.getTarget))
      } else {
        None
      }

      val stats = Option(detectLanguageResult.getStatistics) match {
        case Some(s) => Some(DocumentStatistics(s.getCharacterCount, s.getTransactionCount))
        case None => None
      }

      TAResponseV4[DetectedLanguageV4](
        languageResult,
        error,
        stats,
        Some(detectLanguageResultCollection.getModelVersion))
    }
}

object TextSentimentV4 extends ComplexParamsReadable[TextSentimentV4]
class TextSentimentV4(override val textAnalyticsOptions: Option[TextAnalyticsRequestOptions] = None,
                                     override val uid: String = randomUID("TextSentimentV4"))
  extends TextAnalyticsSDKBase[SentimentScoredDocumentV4](textAnalyticsOptions)
    with HasConfidenceScoreCol {
    logClass()

  // Country Hint
  final val countryHintCol: Param[String] = new Param[String](this, "countryHintCol", "country hint column name")

  final def getCountryHintCol: String = $(countryHintCol)

  final def setCountryHintCol(value: String): this.type = set(countryHintCol, value)
  setDefault(countryHintCol -> "CountryHint")

  override def outputSchema: StructType = SentimentResponseV4.schema
  override protected val invokeTextAnalytics: String => TAResponseV4[SentimentScoredDocumentV4]= (text: String) =>{

  val textSentimentResultCollection = textAnalyticsClient.analyzeSentimentBatch(
      Seq(text).asJava, null, textAnalyticsOptions.orNull)
    val textSentimentResult = textSentimentResultCollection.asScala.head

      System.out.printf("Document ID: %s%n", textSentimentResult.getId());
    val documentSentiment = textSentimentResult.getDocumentSentiment();
    val sentimentResult = if (textSentimentResult.isError) {
     None

 } else {
    Some(SentimentScoredDocumentV4(
      documentSentiment.getSentiment().toString,
      SentimentConfidenceScoreV4(
        documentSentiment.getConfidenceScores().getNegative,
        documentSentiment.getConfidenceScores().getNeutral,
        documentSentiment.getConfidenceScores().getPositive)))

     //documentSentiment.getSentences

//     sentenceSentiment.getSentiment(),
//     sentenceSentiment.getConfidenceScores().getPositive(),
//     sentenceSentiment.getConfidenceScores().getNeutral(),
//     sentenceSentiment.getConfidenceScores().getNegative())

    }
    val error = if(textSentimentResult.isError){
      val error = textSentimentResult.getError
      Some(TAErrorV4(error.getErrorCode.toString, error.getMessage, error.getTarget))
    } else {
      None
    }
    val stats = Option(textSentimentResult.getStatistics) match {
      case Some(s) => Some(DocumentStatistics(s.getCharacterCount, s.getTransactionCount))
      case None => None
    }
    TAResponseV4[SentimentScoredDocumentV4](
      sentimentResult,
      error,
      stats,
      Some(textSentimentResultCollection.getModelVersion))
  }
}
object DetectLanguageResponseV4 extends SparkBindings[TAResponseV4[DetectedLanguageV4]]
object SentimentResponseV4 extends SparkBindings[TAResponseV4[SentimentScoredDocumentV4]]

case class TAResponseV4[T](result: Option[T],
                           error: Option[TAErrorV4],
                           statistics: Option[DocumentStatistics],
                           modelVersion: Option[String])

case class TAErrorV4(errorCode: String, errorMessage: String, target: String)

case class DetectedLanguageV4(name: String, iso6391Name: String, confidenceScore: Double)

case class SentimentScoredDocumentV4(sentiment: String,
                                     confidenceScores: SentimentConfidenceScoreV4)
                                    // sentences: SentenceSentiment)

//case class SentimentSentenceV4(text: Option[String],
//                    sentiment: String,
//                    confidenceScores: SentimentConfidenceScores,
//                    offset: Int,
//                    length: Int)
//
case class SentimentConfidenceScoreV4(negative: Double, neutral: Double, positive: Double)
