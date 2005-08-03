//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2005 The OpenNMS Group, Inc.  All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified 
// and included code are below.
//
// OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
//
// Original code base Copyright (C) 1999-2001 Oculan Corp.  All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.                                                            
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//    
// For more information contact: 
//   OpenNMS Licensing       <license@opennms.org>
//   http://www.opennms.org/
//   http://www.opennms.com/
//
// Tab Size = 8

package org.opennms.netmgt.charts;

import java.awt.Color;
import java.awt.Paint;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.Iterator;

import org.apache.log4j.Category;
import org.exolab.castor.xml.MarshalException;
import org.exolab.castor.xml.ValidationException;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.jdbc.JDBCCategoryDataset;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.config.ChartConfigFactory;
import org.opennms.netmgt.config.DatabaseConnectionFactory;
import org.opennms.netmgt.config.charts.BarChart;
import org.opennms.netmgt.config.charts.ImageSize;
import org.opennms.netmgt.config.charts.Rgb;
import org.opennms.netmgt.config.charts.SeriesDef;
import org.opennms.netmgt.config.charts.SubTitle;
import org.opennms.netmgt.config.charts.Title;

/**
 * @author david
 *
 */
public class ChartUtils {
    
    /**
     * Use this it initialize required factories so that the WebUI doesn't have to.  Can't
     * wait for Spring.
     */
    static {
        try {
            DatabaseConnectionFactory.init();
            ChartConfigFactory.init();
        } catch (MarshalException e) {
            log().error("static initializer: Error marshalling chart configuration. "+e);
        } catch (ValidationException e) {
            log().error("static initializer: Error validating chart configuration. "+e);
        } catch (FileNotFoundException e) {
            log().error("static initializer: Error finding chart configuration. "+e);
        } catch (IOException e) {
            log().error("static initializer: IO error while marshalling chart configuration file. "+e);
        } catch (ClassNotFoundException e) {
            log().error("static initializer: Error initializing database connection factory. "+e);
        }
    }

    /**
     * Logging helper method.
     * 
     * @return A log4j <code>Category</code>.
     */
    private static Category log() {
        return ThreadCategory.getInstance(ChartUtils.class);
    }

    /**
     * This method will returns a JFreeChart bar chart constructed based on XML configuration.
     * 
     * @param chartName Name specified in chart-configuration.xml
     * @return <code>JFreeChart</code> constructed from the chartName
     * @throws MarshalException
     * @throws ValidationException
     * @throws IOException
     * @throws SQLException
     */
    public static JFreeChart getBarChart(String chartName) throws MarshalException, ValidationException, IOException, SQLException {

        BarChart chartConfig = null;
        Connection conn = null;
        chartConfig = getBarChartConfigByName(chartName);
        
        if (chartConfig == null) {
            throw new IllegalArgumentException("getBarChart: Invalid chart name.");
        }
        
        /*
         * Get a database connection and create a JDBC based data set.
         */
        conn = DatabaseConnectionFactory.getInstance().getConnection();
        DefaultCategoryDataset baseDataSet = new DefaultCategoryDataset();
        
        /*
         * Configuration can contain more than one series.  This loop adds
         * single series data sets returned from sql query to a base data set
         * to be displayed in a the chart. 
         */
        Iterator it = chartConfig.getSeriesDefCollection().iterator();
        while (it.hasNext()) {
            SeriesDef def = (SeriesDef) it.next();
            JDBCCategoryDataset dataSet = new JDBCCategoryDataset(conn, def.getJdbcDataSet().getSql());
            
            for (int i = 0; i < dataSet.getRowCount(); i++) {
                for (int j = 0; j < dataSet.getColumnCount(); j++) {
                    baseDataSet.addValue(dataSet.getValue(i, j), def.getSeriesName(), dataSet.getColumnKey(j));
                }
            }
        }

        
        PlotOrientation po = (chartConfig.getPlotOrientation() == "horizontal" ? PlotOrientation.HORIZONTAL : PlotOrientation.VERTICAL);
        
        JFreeChart barChart = ChartFactory.createBarChart(chartConfig.getTitle().getValue(),
                chartConfig.getDomainAxisLabel(),
                chartConfig.getRangeAxisLabel(),
                baseDataSet,
                po,
                chartConfig.getShowLegend(),
                chartConfig.getShowToolTips(),
                chartConfig.getShowUrls());
        
        /*
         * Add subtitles.
         */
        for (it = chartConfig.getSubTitleCollection().iterator(); it.hasNext();) {
            SubTitle subTitle = (SubTitle) it.next();
            Title title = subTitle.getTitle();
            String value = title.getValue();
            barChart.addSubtitle(new TextTitle(value));
        }
        
        /*
         * Set the series colors and labels
         */
        CategoryPlot plot = barChart.getCategoryPlot();
        BarRenderer renderer = (BarRenderer)plot.getRenderer();
        
        CategoryItemLabelGenerator generator = new StandardCategoryItemLabelGenerator("{2}", new DecimalFormat("0"));
        SeriesDef[] seriesDefs = chartConfig.getSeriesDef();

        for (int i = 0; i < seriesDefs.length; i++) {
            SeriesDef seriesDef = seriesDefs[i];
            Rgb rgb = seriesDef.getRgb();
            Paint paint = new Color(rgb.getRed().getRgbColor(), rgb.getGreen().getRgbColor(), rgb.getBlue().getRgbColor());
            renderer.setSeriesPaint(i, paint);
            renderer.setSeriesItemLabelsVisible(i, seriesDef.getUseLabels());
            renderer.setSeriesItemLabelGenerator(i, generator);
        }

        return barChart;
        
    }
    
    /**
     * Helper method that returns the JFreeChart to an output stream written in JPEG format.
     * @param chartName
     * @param out
     * @throws MarshalException
     * @throws ValidationException
     * @throws IOException
     * @throws SQLException
     */
    public static void getBarChart(String chartName, OutputStream out) throws MarshalException, ValidationException, IOException, SQLException {
        BarChart chartConfig = getBarChartConfigByName(chartName);
        JFreeChart chart = getBarChart(chartName);
        ImageSize imageSize = chartConfig.getImageSize();
        int hzPixels;
        int vtPixels;
        
        if (imageSize == null) {
            hzPixels = 400;
            vtPixels = 400;
        } else {            
            hzPixels = imageSize.getHzSize().getPixels();
            vtPixels = imageSize.getVtSize().getPixels();
        }
        
        ChartUtilities.writeChartAsJPEG(out, chart, hzPixels, vtPixels);
        
    }
    
    /**
     * Helper method that returns the JFreeChart as a PNG byte array.
     * 
     * @param chartName
     * @return a byte array
     * @throws MarshalException
     * @throws ValidationException
     * @throws IOException
     * @throws SQLException
     */
    public static byte[] getBarChartAsPNGByteArray(String chartName) throws MarshalException, ValidationException, IOException, SQLException {
        BarChart chartConfig = getBarChartConfigByName(chartName);
        JFreeChart chart = getBarChart(chartName);
        ImageSize imageSize = chartConfig.getImageSize();
        int hzPixels;
        int vtPixels;
        
        if (imageSize == null) {
            hzPixels = 400;
            vtPixels = 400;
        } else {            
            hzPixels = imageSize.getHzSize().getPixels();
            vtPixels = imageSize.getVtSize().getPixels();
        }
        return ChartUtilities.encodeAsPNG(chart.createBufferedImage(hzPixels, vtPixels));
    }
    
    /**
     * Helper method used to return a JFreeChart as a buffered Image.
     * 
     * @param chartName
     * @return a <code>BufferedImage</code>
     * @throws MarshalException
     * @throws ValidationException
     * @throws IOException
     * @throws SQLException
     */
    public static BufferedImage getChartAsBufferedImage(String chartName) throws MarshalException, ValidationException, IOException, SQLException {
        BarChart chartConfig = getBarChartConfigByName(chartName);
        JFreeChart chart = getBarChart(chartName);
        ImageSize imageSize = chartConfig.getImageSize();
        int hzPixels;
        int vtPixels;
        
        if (imageSize == null) {
            hzPixels = 400;
            vtPixels = 400;
        } else {            
            hzPixels = imageSize.getHzSize().getPixels();
            vtPixels = imageSize.getVtSize().getPixels();
        }

        return chart.createBufferedImage(hzPixels, vtPixels);
        
    }
    
    /**
     * Helper method used to retrieve the XML defined BarChart (castor class)
     * 
     * @param chartName
     * @return a derived Castor class: BarChart
     * @throws MarshalException
     * @throws ValidationException
     * @throws IOException
     */
    public static BarChart getBarChartConfigByName(String chartName) throws MarshalException, ValidationException, IOException {
        Iterator it = getChartCollectionIterator();
        BarChart chart = null;
        while (it.hasNext()) {
            chart = (BarChart)it.next();
            if (chart.getName().equals(chartName))
                return chart;
        }
        return null;
    }
    
    /**
     * Helper method used to fetch an Iterator for all defined Charts
     * 
     * @return <code>BarChart</code> Iterator
     * @throws IOException
     * @throws MarshalException
     * @throws ValidationException
     */
    public static Iterator getChartCollectionIterator() throws IOException, MarshalException, ValidationException {
        return ChartConfigFactory.getInstance().getConfiguration().getBarChartCollection().iterator();
    }
    
}
