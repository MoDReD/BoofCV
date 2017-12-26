/*
 * Copyright (c) 2011-2017, Peter Abeles. All Rights Reserved.
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

package boofcv.demonstrations.fiducial;

import boofcv.alg.fiducial.qrcode.QrCode;
import boofcv.gui.BoofSwingUtil;
import boofcv.gui.StandardAlgConfigPanel;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Peter Abeles
 */
public class DetectQrCodeMessagePanel extends StandardAlgConfigPanel
implements ListSelectionListener
{

	Listener listener;
	JList listDetected;
	JTextArea textArea = new JTextArea();

	List<QrCode> detected = new ArrayList<>();
	List<QrCode> failures = new ArrayList<>();

	public DetectQrCodeMessagePanel(Listener listener ) {
		this.listener = listener;

		listDetected = new JList();
		listDetected.setModel(new DefaultListModel());
		listDetected.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
		listDetected.setLayoutOrientation(JList.VERTICAL);
		listDetected.setVisibleRowCount(-1);
		listDetected.addListSelectionListener(this);

		textArea.setEditable(false);

		// ensures that the split pane can be dragged
		Dimension minimumSize = new Dimension(0, 0);
		listDetected.setMinimumSize(minimumSize);
		textArea.setMinimumSize(minimumSize);

		JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(listDetected),textArea);
		splitPane.setDividerLocation(150);

		addAlignCenter(new JLabel("QR-Codes"));
		addAlignCenter(splitPane);
	}

	public void updateList(List<QrCode> detected , List<QrCode> failures ) {
		BoofSwingUtil.checkGuiThread();

		this.listDetected.removeListSelectionListener(this);
		DefaultListModel<String>  model = (DefaultListModel)listDetected.getModel();
		model.clear();

		this.detected.clear();
		for (int i = 0; i < detected.size(); i++) {
			QrCode qr = detected.get(i);
			model.addElement(String.format("Ver. %2d Mode %s. %10s",qr.version,qr.mode.toString(),qr.message.toString()));
			this.detected.add( qr.clone() );
		}
		this.failures.clear();
		for (int i = 0; i < failures.size(); i++) {
			QrCode qr = failures.get(i);
			model.addElement(String.format("Ver. %2d Mode %s. FAILED",qr.version,qr.mode.toString()));
			this.failures.add( qr.clone() );
		}
		listDetected.invalidate();
		listDetected.repaint();

		textArea.setText("");

		this.listDetected.addListSelectionListener(this);
	}


	@Override
	public void valueChanged(ListSelectionEvent e) {
		if( e.getValueIsAdjusting() )
			return;
		if( e.getSource() == listDetected ) {
			int selected = listDetected.getSelectedIndex();
			if (selected == -1)
				return;

			boolean failed = selected >= detected.size();

			if (failed) {
				selected -= detected.size();
				QrCode qr = failures.get(selected);
				listener.selectedMarker(selected,true);
				textArea.setText(String.format("Version %2d\nMode %s\nMask %s\n\n%s",
						qr.version, qr.mode==null?"":qr.mode,qr.mask==null?"":qr.mask,qr.failureCause.toString()));
			} else {
				listener.selectedMarker(selected,false);
				QrCode qr = detected.get(selected);
				textArea.setText(String.format("Version %2d\nMode %s\nMask %s\n\n%s",
						qr.version, qr.mode.toString(), qr.mask.toString(), qr.message));
			}
			textArea.invalidate();

		}
	}

	public interface Listener {
		void selectedMarker( int index , boolean failure );
	}
}
