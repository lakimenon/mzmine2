/*
 * Copyright 2006-2009 The MZmine 2 Development Team
 * 
 * This file is part of MZmine 2.
 * 
 * MZmine 2 is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine 2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine 2; if not, write to the Free Software Foundation, Inc., 51 Franklin St,
 * Fifth Floor, Boston, MA 02110-1301 USA
 */


package net.sf.mzmine.modules.visualization.scatterplot.plottooltip;

import java.text.NumberFormat;
import java.util.Vector;

import javax.swing.table.AbstractTableModel;

import net.sf.mzmine.data.ChromatographicPeak;
import net.sf.mzmine.data.RawDataFile;
import net.sf.mzmine.main.MZmineCore;

public class ToolTipTableModel extends AbstractTableModel{

	private static NumberFormat mzFormat = MZmineCore.getMZFormat();
	private static NumberFormat rtFormat = MZmineCore.getRTFormat();
	private static NumberFormat intensityFormat = MZmineCore
			.getIntensityFormat();
	
	private Vector<ChromatographicPeak> peaks = new Vector<ChromatographicPeak>();
	
    private static String[] columnNames = {"File Name",
        "Mass",
        "RT",
        "Height",
        "Area"};

    public String getColumnName(int col) {
        return columnNames[col].toString();
    }
    
    public int getRowCount() { 
    	return peaks.size();
    }
    
    public int getColumnCount() { 
    	return columnNames.length;
    }
    
    public Object getValueAt(int row, int col) {
    	Object value = null;
    	ChromatographicPeak peak = peaks.get(row);
    	switch(col){
    	case (0):
    		value = peak.getDataFile().getFileName();
    		break;
    	case(1): 
    		value = mzFormat.format(peak.getMZ());
			break;
    	case(2): 
    		value = rtFormat.format(peak.getRT());
			break;
    	case(3):
    		value = intensityFormat.format(peak.getHeight());
			break;
    	case(4):
    		value = intensityFormat.format(peak.getArea());
		break;
    	}

    	return value;
    }
    
    public ChromatographicPeak getElementAt(int row){
    	return peaks.get(row);
    }
    
    public boolean isCellEditable(int row, int col){ 
    	return false;
    }
    
    public void addElement(ChromatographicPeak peak){
    	peaks.add(peak);
    	fireTableRowsInserted(0,peaks.size()-1);
    }
    
    public void setValueAt(Object value, int row, int col){
    }
    
    public int getIndexRow(String fileName){
    	
    	String localFileName;
    	int index = -1;
    	for (int i=0; i< peaks.size(); i++){
    		localFileName = peaks.get(i).getDataFile().getFileName();
    		if (localFileName.equals(fileName)){
    			index = i;
    		}
    	}
    	
    	return index;
    }


}