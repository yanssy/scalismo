/*
 * Copyright 2015 University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scalismo.statisticalmodel.dataset

import java.io.File

import scalismo.ScalismoTestSuite
import scalismo.common.{ Vectorizer, Field, VectorField }
import scalismo.geometry._
import scalismo.io.MeshIO
import scalismo.kernels.{ Kernel, DiagonalKernel, GaussianKernel }
import scalismo.mesh.MeshMetrics
import scalismo.numerics.UniformMeshSampler3D
import scalismo.registration.{ LandmarkRegistration, TranslationTransform }
import scalismo.statisticalmodel.{ LowRankGaussianProcess, GaussianProcess, StatisticalMeshModel }
import scalismo.utils.Random

class DataCollectionTests extends ScalismoTestSuite {

  describe("A datacollection") {

    implicit val random = Random(42)

    val transformations = for (i <- 0 until 10) yield TranslationTransform(Vector(i.toDouble, 0.0, 0.0))
    val dataItems = for ((t, i) <- transformations.zipWithIndex) yield DataItem(s"transformation-$i", t)
    val meshpath = getClass.getResource("/facemesh.stl").getPath
    val referenceMesh = MeshIO.readMesh(new File(meshpath)).get

    val dataCollection = DataCollection(referenceMesh, dataItems)

    it("yields the right number of cross-validation folds") {
      def createFolds(nFolds: Int) = {
        dataCollection.createCrossValidationFolds(nFolds)
      }

      createFolds(1).size should be(1)
      createFolds(4).size should be(4)
      createFolds(2).size should be(2)
      dataCollection.createLeaveOneOutFolds.size should be(dataItems.size)
    }

    it("considers every dataset in a leave one out test") {
      val folds = dataCollection.createLeaveOneOutFolds

      // if we accumulated all the testing datasets, we should get all dataItems back. 
      val accumulatedTestingData = folds.foldLeft(Seq[DataItem[_3D]]())((acc, di) => acc :+ di.testingData.dataItems(0))
      val sortedAccTestData = accumulatedTestingData.sortWith((a, b) => a.info > b.info)
      val sortedDataItems = dataCollection.dataItems.sortWith((a, b) => a.info > b.info)
      sortedAccTestData should equal(sortedDataItems)

    }

    it("yields the right fold sizes for a leave one out test") {
      for (fold <- dataCollection.createLeaveOneOutFolds) {
        fold.trainingData.size should be(dataCollection.size - 1)
        fold.testingData.size should be(1)
        fold.trainingData.dataItems.contains(fold.testingData) should be(false)
      }
    }

    it("has all distinct training datasets in a leave one out test") {
      val folds = dataCollection.createLeaveOneOutFolds
      for (fold <- folds) {
        fold.trainingData.dataItems.toSet.size should be(fold.trainingData.size)
      }

    }

  }

  object Fixture {

    val nonAlignedFaces = new File(getClass.getResource("/nonAlignedFaces").getPath).listFiles.sortBy(_.getName).map { f => MeshIO.readMesh(f).get }.toIndexedSeq
    val ref = nonAlignedFaces.head
    val dataset = nonAlignedFaces.tail

    val aligendDataset = dataset.map { d =>
      val trans = LandmarkRegistration.rigid3DLandmarkRegistration((d.pointSet.points zip ref.pointSet.points).toIndexedSeq, Point(0, 0, 0))
      d.transform(trans)
    }

    val trainingSet = aligendDataset.drop(3)
    val testingSet = aligendDataset.take(3)

    val dc = DataCollection.fromMeshSequence(ref, trainingSet)._1.get
    val pcaModel = StatisticalMeshModel.createUsingPCA(dc).get
    val testDC = DataCollection.fromMeshSequence(pcaModel.referenceMesh, testingSet)._1.get

  }

  describe("GPA") {
    it("leads to smaller average distances to collection's reference") {
      val gpaDC = DataCollection.gpa(Fixture.dc)
      val errSumDC = Fixture.dc.dataItems.map(i => MeshMetrics.avgDistance(Fixture.dc.reference, Fixture.dc.reference.transform(i.transformation))).sum
      val errSumGpaDC = gpaDC.dataItems.map(i => MeshMetrics.avgDistance(gpaDC.reference, gpaDC.reference.transform(i.transformation))).sum
      assert(errSumGpaDC < errSumDC)
    }

  }

  describe("Generalization") {

    implicit val random = Random(42)

    val zeroMean = Field(Fixture.dc.reference.boundingBox, (pt: Point[_3D]) => Vector(0, 0, 0))
    val matrixValuedGaussian = DiagonalKernel(GaussianKernel[_3D](25) * 20, 3)
    val bias: GaussianProcess[_3D, Vector[_3D]] = GaussianProcess(zeroMean, matrixValuedGaussian)
    val biasLowRank = LowRankGaussianProcess.approximateGP(bias, UniformMeshSampler3D(Fixture.pcaModel.referenceMesh, 500), Fixture.pcaModel.rank + 5)
    val augmentedModel = StatisticalMeshModel.augmentModel(Fixture.pcaModel, biasLowRank)

    it("gives the same values when evaluated 10 times on normal PCA Model") {
      val gens = (0 until 10) map { _ => ModelMetrics.generalization(Fixture.pcaModel, Fixture.testDC).get }
      assert(gens.forall(_ - gens(0) < 1.0e-14))
    }

    it("gives the same values when evaluated 10 times on augmented model") {
      val gens = (0 until 10) map { _ => ModelMetrics.generalization(augmentedModel, Fixture.testDC).get }
      assert(gens.forall(_ - gens(0) < 1.0e-14))
    }

    it("improves when the model is augmented with a Gaussian") {

      (0 until 10) foreach { i =>
        val genAugmented = ModelMetrics.generalization(augmentedModel, Fixture.testDC).get
        val genOriginal = ModelMetrics.generalization(Fixture.pcaModel, Fixture.testDC).get

        assert(genAugmented < genOriginal)
      }
    }
  }

}
