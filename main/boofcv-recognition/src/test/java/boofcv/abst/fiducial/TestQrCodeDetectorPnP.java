/*
 * Copyright (c) 2011-2018, Peter Abeles. All Rights Reserved.
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

package boofcv.abst.fiducial;

import boofcv.alg.distort.LensDistortionNarrowFOV;
import boofcv.alg.distort.pinhole.LensDistortionPinhole;
import boofcv.alg.distort.radtan.LensDistortionRadialTangential;
import boofcv.alg.fiducial.qrcode.QrCode;
import boofcv.alg.fiducial.qrcode.QrCodeEncoder;
import boofcv.alg.fiducial.qrcode.QrCodeGeneratorImage;
import boofcv.core.image.GConvertImage;
import boofcv.factory.fiducial.FactoryFiducial;
import boofcv.simulation.SimulatePlanarWorld;
import boofcv.struct.calib.CameraPinholeRadial;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageType;
import georegression.struct.se.Se3_F64;
import georegression.struct.se.SpecialEuclideanOps_F64;

/**
 * @author Peter Abeles
 */
public class TestQrCodeDetectorPnP extends GenericFiducialDetectorChecks {

	CameraPinholeRadial intrinsic = new CameraPinholeRadial(400,400,0,300,300,600,600).fsetRadial(-0.256,0.0999);

	public TestQrCodeDetectorPnP() {
		types.add( ImageType.single(GrayU8.class));
		types.add( ImageType.single(GrayF32.class));
		pixelAndProjectedTol = 1.0; // should be very close
	}

	@Override
	public ImageBase loadImage(ImageType imageType) {
		QrCode qr = new QrCodeEncoder().addAutomatic("THE MESSAGE").fixate();

		QrCodeGeneratorImage generator = new QrCodeGeneratorImage(6);
		generator.setBorderModule(0);
		generator.render(qr);

		GrayF32 fiducial = generator.getGrayF32();

		Se3_F64 fidToWorld = SpecialEuclideanOps_F64.eulerXyz(0,0,0.7,0.3,Math.PI,0,null);

		SimulatePlanarWorld sim = new SimulatePlanarWorld();
		sim.setBackground(255);
		sim.setCamera(intrinsic);
		sim.addSurface(fidToWorld,0.3,fiducial);

		sim.render();

		GrayF32 simulated = sim.getOutput();

		if( imageType.isSameType(simulated.imageType))
			return simulated;

		ImageBase out = imageType.createImage(simulated.width,simulated.height);
		GConvertImage.convert(simulated,out);

		return out;
	}

	@Override
	public LensDistortionNarrowFOV loadDistortion(boolean distorted) {
		if( distorted )
			return new LensDistortionRadialTangential(intrinsic);
		else
			return new LensDistortionPinhole(intrinsic);
	}

	@Override
	public FiducialDetector createDetector(ImageType imageType) {
		return FactoryFiducial.qrcode3D(null,imageType.getImageClass());
	}
}