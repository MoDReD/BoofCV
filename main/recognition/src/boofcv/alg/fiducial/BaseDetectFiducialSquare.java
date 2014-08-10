/*
 * Copyright (c) 2011-2014, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.fiducial;

import boofcv.abst.filter.binary.InputToBinary;
import boofcv.alg.distort.*;
import boofcv.alg.feature.shapes.SplitMergeLineFitLoop;
import boofcv.alg.filter.binary.Contour;
import boofcv.alg.filter.binary.LinearContourLabelChang2004;
import boofcv.alg.geo.PerspectiveOps;
import boofcv.alg.geo.calibration.Zhang99DecomposeHomography;
import boofcv.alg.geo.h.HomographyLinear4;
import boofcv.core.image.border.BorderType;
import boofcv.core.image.border.FactoryImageBorder;
import boofcv.factory.distort.FactoryDistort;
import boofcv.factory.interpolate.FactoryInterpolation;
import boofcv.struct.ConnectRule;
import boofcv.struct.calib.IntrinsicParameters;
import boofcv.struct.distort.PixelTransform_F32;
import boofcv.struct.distort.SequencePointTransform_F32;
import boofcv.struct.geo.AssociatedPair;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSInt32;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.ImageUInt8;
import georegression.struct.homography.UtilHomography;
import georegression.struct.point.Point2D_F64;
import georegression.struct.point.Point2D_I32;
import georegression.struct.se.Se3_F64;
import georegression.struct.shapes.Quadrilateral_F64;
import org.ddogleg.struct.FastQueue;
import org.ddogleg.struct.GrowQueue_I32;
import org.ejml.UtilEjml;
import org.ejml.data.DenseMatrix64F;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * Target orientation. Corner 0 = (r,r), 1 = (r,-r) , 2 = (-r,-r) , 3 = (-r,r).
 *
 * @author Peter Abeles
 */
// TODO create a tracking algorithm which uses previous frame information for speed + stability
public abstract class BaseDetectFiducialSquare<T extends ImageSingleBand> {

	// how wide the entire target is
	double targetWidth;

	FastQueue<FoundFiducial> found = new FastQueue<FoundFiducial>(FoundFiducial.class,true);

	// Converts the input image into a binary one
	InputToBinary<T> thresholder;

	// minimum size of a shape's contour
	int minimumContour = 200;
	double minimumArea = Math.pow(minimumContour/4.0,2);

	ImageUInt8 binary = new ImageUInt8(1,1);
	ImageUInt8 temp0 = new ImageUInt8(1,1);
	ImageUInt8 temp1 = new ImageUInt8(1,1);

	// precomputed lookup table for distorted to undistorted pixel locations
	private Point2D_I32 map[];
	FastQueue<Point2D_I32> contourUndist = new FastQueue<Point2D_I32>(Point2D_I32.class,false);

	LinearContourLabelChang2004 contourFinder = new LinearContourLabelChang2004(ConnectRule.FOUR);
	ImageSInt32 labeled = new ImageSInt32(1,1);

	SplitMergeLineFitLoop fitPolygon;

	FastQueue<Quadrilateral_F64> candidates = new FastQueue<Quadrilateral_F64>(Quadrilateral_F64.class,true);

	protected ImageFloat32 square;

	HomographyLinear4 computeHomography = new HomographyLinear4(true);
	DenseMatrix64F H = new DenseMatrix64F(3,3);
	AddRadialPtoP_F32 addDistortion = new AddRadialPtoP_F32();
	List<AssociatedPair> pairsRemovePerspective = new ArrayList<AssociatedPair>();
	ImageDistort<T,ImageFloat32> removePerspective;
	PointTransformHomography_F32 transformHomography = new PointTransformHomography_F32();

	FitQuadrilaterialEM fitQuad = new FitQuadrilaterialEM();

	IntrinsicParameters intrinsic;

	Result result = new Result();

	// type of input image
	Class<T> inputType;

	// ----- Used to estimate 3D pose of calibration target

	// p1 is set to the corner locations in 2D target frame. [0] is top right (upper extreme)
	// and the points are added in clock-wise direction.  The center of the target is
	// the center of the coordinate system
	List<AssociatedPair> pairsPose = new ArrayList<AssociatedPair>();
	Zhang99DecomposeHomography homographyToPose = new Zhang99DecomposeHomography();

	protected BaseDetectFiducialSquare(InputToBinary<T> thresholder,
									   SplitMergeLineFitLoop fitPolygon,
									   int squarePixels,
									   Class<T> inputType ) {

		this.thresholder = thresholder;
		this.inputType = inputType;
		this.square = new ImageFloat32(squarePixels,squarePixels);

		this.fitPolygon = fitPolygon;

		for (int i = 0; i < 4; i++) {
			pairsRemovePerspective.add(new AssociatedPair());
			pairsPose.add( new AssociatedPair());
		}

		removePerspective = FactoryDistort.distort(false,FactoryInterpolation.bilinearPixelS(inputType),
				FactoryImageBorder.general(inputType, BorderType.EXTENDED),ImageFloat32.class);
		SequencePointTransform_F32 sequence = new SequencePointTransform_F32(transformHomography,addDistortion);
		PixelTransform_F32 squareToInput= new PointToPixelTransform_F32(sequence);

		removePerspective.setModel(squareToInput);

		map = new Point2D_I32[0];
	}

	public void setTargetShape( double squareWidth ) {
		this.targetWidth = squareWidth;

		// add corner points in target frame
		pairsPose.get(0).p1.set( squareWidth / 2,  squareWidth / 2);
		pairsPose.get(1).p1.set( squareWidth / 2, -squareWidth / 2);
		pairsPose.get(2).p1.set(-squareWidth / 2, -squareWidth / 2);
		pairsPose.get(3).p1.set(-squareWidth / 2, squareWidth / 2);
	}

	/**
	 * Specifies the image's intrinsic parameters
	 * @param intrinsic Intrinsic parameters for the distortion free input image
	 */
	public void setIntrinsic(IntrinsicParameters intrinsic) {
		this.intrinsic = intrinsic;

		binary.reshape(intrinsic.width,intrinsic.height);
		temp0.reshape(intrinsic.width, intrinsic.height);
		temp1.reshape(intrinsic.width, intrinsic.height);
		labeled.reshape(intrinsic.width,intrinsic.height);

		DenseMatrix64F K = new DenseMatrix64F(3,3);
		PerspectiveOps.calibrationMatrix(intrinsic, K);
		homographyToPose.setCalibrationMatrix(K);

		RemoveRadialPtoP_F64 removeDistort = new RemoveRadialPtoP_F64();
		removeDistort.set(intrinsic.fx,intrinsic.fy,intrinsic.skew,intrinsic.cx,intrinsic.cy,intrinsic.radial);
		addDistortion.set(intrinsic.fx,intrinsic.fy,intrinsic.skew,intrinsic.cx,intrinsic.cy,intrinsic.radial);
		int N = intrinsic.width*intrinsic.height;
		Point2D_F64 p = new Point2D_F64();
		if( map.length < N ) {
			map = new Point2D_I32[N];
			for (int i = 0; i < N; i++) {
				map[i] = new Point2D_I32();
				removeDistort.compute(i%intrinsic.width,i/intrinsic.width,p);
				map[i].set( (int)Math.round(p.x) , (int)Math.round(p.y) );
			}
		}
	}

	/**
	 *
	 * @param gray Input image with lens distortion removed
	 */
	public void process( T gray ) {

		found.reset();
		candidates.reset();

		thresholder.process(gray, binary);

		// Find quadrilaterials that could be fiducials
		findCandidateShapes();

		// undistort the squares
		for (int i = 0; i < candidates.size; i++) {
			// compute the homography from the input image to an undistorted square image
			Quadrilateral_F64 q = candidates.get(i);

			pairsRemovePerspective.get(0).set( 0              ,    0            , q.a.x , q.a.y);
			pairsRemovePerspective.get(1).set( square.width-1 ,    0            , q.b.x , q.b.y );
			pairsRemovePerspective.get(2).set( square.width-1 , square.height-1 , q.c.x , q.c.y );
			pairsRemovePerspective.get(3).set( 0              , square.height-1 , q.d.x , q.d.y );

			computeHomography.process(pairsRemovePerspective,H);
			// pass the found homography onto the image transform
			UtilHomography.convert(H,transformHomography.getModel());
			// remove the perspective distortion and process it
			removePerspective.apply(gray, square);
			if( processSquare(square,result)) {
				FoundFiducial f = found.grow();
				f.index = result.which;
				f.location.set(q);

				// account for the rotation
				for (int j = 0; j < result.rotation; j++) {
					rotateClockWise(q);
				}

				// estimate position
				computeTargetToWorld(q,f.targetToSensor);
			}
		}
	}

	private void rotateClockWise( Quadrilateral_F64 quad ) {
		Point2D_F64 a = quad.a;
		Point2D_F64 b = quad.b;
		Point2D_F64 c = quad.c;
		Point2D_F64 d = quad.d;

		quad.a = d;
		quad.b = a;
		quad.c = b;
		quad.d = c;
	}

	private void findCandidateShapes() {
		// find binary blobs
		contourFinder.process(binary, labeled);

		int endX = binary.width-1;
		int endY = binary.height-1;

		// find blobs where all 4 edges are lines
		FastQueue<Contour> blobs = contourFinder.getContours();
		for (int i = 0; i < blobs.size; i++) {
			Contour c = blobs.get(i);

			// can't be entirely black
			if( c.internal.isEmpty() )
				continue;

			if( c.external.size() >= minimumContour) {
				// if it touches the image border skip. Speeds thinks up a little bit when adaptive thresholding
				// is used due to the number of large false positives
				// This is reasonable since you can't estimate the location without the entire target border
				boolean skip = false;
				contourUndist.reset();
				for (int j = 0; j < c.external.size(); j++) {
					Point2D_I32 p = c.external.get(j);
					if( p.x == 0 || p.y == 0 || p.x == endX || p.y == endY )
					{
						skip = true;
						break;
					} else {
						// look up undistorted pixel locations
						contourUndist.add( map[p.y*binary.width+p.x]);
					}
				}
				if( skip )
					continue;

				fitPolygon.process(contourUndist.toList());
				GrowQueue_I32 splits = fitPolygon.getSplits();

				// If there are too many splits it's probably not a quadrilateral
				if( splits.size <= 8 && splits.size >= 4 ) {

					Quadrilateral_F64 q = candidates.grow();
					if( !fitQuad.fit(contourUndist.toList(),splits,q) ) {
						candidates.removeTail();
					} else {
						// remove small and flat out bad shapes
						double area = q.area();
						if(UtilEjml.isUncountable(area) || area < minimumArea ) {
							candidates.removeTail();
						}
					}
				}
			}
		}
	}

	/**
	 * Given observed location of corners, compute the transform from target to world frame.
	 * See code comments for correct ordering of corners in quad.
	 *
	 * @param quad (Input) Observed location of corner points in the specified order.
	 * @param targetToWorld (output) transform from target to world frame.
	 */
	public void computeTargetToWorld( Quadrilateral_F64 quad , Se3_F64 targetToWorld ) {
		pairsPose.get(0).p2.set(quad.a);
		pairsPose.get(1).p2.set(quad.b);
		pairsPose.get(2).p2.set(quad.c);
		pairsPose.get(3).p2.set(quad.d);

		if( !computeHomography.process(pairsPose,H) )
			throw new RuntimeException("Compute homography failed!");

		targetToWorld.set(homographyToPose.decompose(H));
	}

	/**
	 * Returns list of found fiducials
	 */
	public FastQueue<FoundFiducial> getFound() {
		return found;
	}

	/**
	 * Processes the detected square and matches it to a known fiducial.  Black border
	 * is included.
	 *
	 * @param square Image of the undistorted square
	 * @param result Which target and its orientation was found
	 * @return true if the square matches a known target.
	 */
	protected abstract boolean processSquare( ImageFloat32 square , Result result );

	public Class<T> getInputType() {
		return inputType;
	}

	public static class Result {
		int which;
		int rotation;
	}
}