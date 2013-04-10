/*
 * Copyright (c) 2013 by Roman Seidl - romanAeTgranul.at
 * 
 *  This Program uses code copyright (c) 2012 by David Shepard
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package at.granul.gephi.shpexporter;

import at.granul.gephi.shpexporter.ui.SHPExporterDialog;
import com.hypercities.exporttoearth.GeoAttributeFinder;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gephi.data.attributes.api.AttributeColumn;
import org.gephi.data.attributes.api.AttributeController;
import org.gephi.data.attributes.api.AttributeModel;
import org.gephi.data.attributes.api.AttributeRow;
import org.gephi.graph.api.Node;
import org.gephi.preview.api.Item;
import org.gephi.preview.api.PreviewController;
import org.gephi.preview.api.PreviewModel;
import org.gephi.preview.plugin.items.NodeItem;
import org.gephi.data.attributes.api.AttributeType;
import org.gephi.graph.api.Edge;
import org.gephi.preview.plugin.items.EdgeItem;

import org.openide.util.Lookup;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;

import org.geotools.data.DefaultTransaction;
import org.geotools.data.Transaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.feature.FeatureCollections;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

/**
 *
 * @author SeidlR
 */
public class SHPExporter {

    private static final String LOCATION_FIELD = "location";
    private static final String SIZE_FIELD = "gSize";
    private static final String COLOR_FIELD = "gColor";

    public boolean execute() {
        try {
            PreviewModel previewModel;
            final PreviewController previewController = Lookup.getDefault().lookup(PreviewController.class);

            //there seems to be a bug in gephi or in gephi & eclipse that needs a refresh of the preview - else the model is empty
            previewController.refreshPreview();

            previewModel = previewController.getModel();
            AttributeModel model = Lookup.getDefault().lookup(AttributeController.class).getModel();
            AttributeColumn[] nodeColums = model.getNodeTable().getColumns();

            //try to find the GeoFields
            AttributeColumn[] geoFields;
            GeoAttributeFinder gaf = new GeoAttributeFinder();
            geoFields = gaf.findGeoFields(nodeColums);

            SHPExporterDialog exporterDialog;
            exporterDialog = new SHPExporterDialog(nodeColums, geoFields);
            exporterDialog.setTitle("SHP Export Options");
            if (exporterDialog.showDialog()) {
                geoFields = exporterDialog.getGeoFields();
                File exportFile = exporterDialog.getFile();

                //Construct Export Filenames
                String baseName = exportFile.getName();
                baseName = baseName.substring(0, baseName.lastIndexOf("."));
                File pointFile = new File(exportFile.getParentFile(), baseName + ".node.shp");
                File edgeFile = new File(exportFile.getParentFile(), baseName + ".edge.shp");

                //convert data to pointFeatureSource
                SimpleFeatureType pointFeatureType = getFeatureTypeForAttributes(Point.class, nodeColums);
                SimpleFeatureCollection pointFeatureSource;
                pointFeatureSource = getPointFeatureSource(previewModel, pointFeatureType, geoFields);

                //convert data to edgeFeatureSource
                AttributeColumn[] edgeColums = model.getEdgeTable().getColumns();
                SimpleFeatureType edgeFeatureType = getFeatureTypeForAttributes(LineString.class, edgeColums);
                SimpleFeatureCollection edgeFeatureSource;
                edgeFeatureSource = getFeatureSource(false, previewModel, edgeFeatureType, geoFields);


                //Create Shapefile
                //Netbean securit-manager ist running wild - dunno what to do but cathc the exception
                writeSHP(pointFile.toURL(), pointFeatureType, pointFeatureSource);

                writeSHP(edgeFile.toURL(), edgeFeatureType, edgeFeatureSource);
                return true;
            }
        } catch (IOException ex) {
            Logger.getLogger(SHPExporter.class.getName()).log(Level.SEVERE, null, ex);
        }
        return true;
    }

    //Builds a SimpleFeatureType from all exportable Columns plus location, color and size
    private SimpleFeatureType getFeatureTypeForAttributes(Class geometryClass, AttributeColumn[] nodeColums) {
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName(geometryClass.getName());

        //Add a geometry
        builder.add(LOCATION_FIELD, geometryClass);

        for (AttributeColumn col : nodeColums) {
            String name = col.getTitle();
            AttributeType typ = col.getType();

            //ignore Lists? 
            if (!typ.isListType()) {
                builder.add(name, typ.getType());
            }
        }
        //add size and color attributes
        builder.add(SIZE_FIELD, Float.class);
        builder.add(COLOR_FIELD, String.class);

        //build the type
        SimpleFeatureType featureType = builder.buildFeatureType();
        return featureType;
    }

    //Converts via parsing a String - might be risky as for localisation?
    private double getDoubleForCoordinateFieldObject(Object value) {
        return Double.parseDouble(value.toString());
    }

    //Produce a geotools Point FeatureCollection from the Graph
    private SimpleFeatureCollection getPointFeatureSource(PreviewModel previewModel, SimpleFeatureType featureType, AttributeColumn[] geoFields) {
        boolean isPoints = true;
        SimpleFeatureCollection collection = getFeatureSource(isPoints, previewModel, featureType, geoFields);
        return collection;
    }

    //Produce a geotools FeatureCollection from the Graph
    private SimpleFeatureCollection getFeatureSource(boolean isPoints, PreviewModel previewModel, SimpleFeatureType featureType, AttributeColumn[] geoFields) {
        String sizeField = isPoints ? NodeItem.SIZE : EdgeItem.WEIGHT;
        String itemType = isPoints ? Item.NODE : Item.EDGE;
        String colorType = isPoints ? NodeItem.COLOR : EdgeItem.COLOR;
        //Helper to create the Point
        GeometryFactory geometryFactory = JTSFactoryFinder.getGeometryFactory();
        SimpleFeatureCollection collection = FeatureCollections.newCollection();
        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
        //Iterate through Nodes
        for (org.gephi.preview.api.Item ni : previewModel.getItems(itemType)) {

            AttributeRow row = isPoints ? (AttributeRow) ((Node) ni.getSource()).getNodeData().getAttributes()
                    : (AttributeRow) ((Edge) ni.getSource()).getEdgeData().getAttributes();

            //Iterate over the columns to fill
            for (org.opengis.feature.type.AttributeType at : featureType.getTypes()) {
                String name = at.getName().getLocalPart();
                if (name.equals(LOCATION_FIELD)) {
                    if (isPoints) {
                        Coordinate coordinate = getCoordinateForNode((Node) ni.getSource(), geoFields);
                        Point point = geometryFactory.createPoint(coordinate);
                        featureBuilder.add(point);
                    } else {
                        final Edge e = (Edge) ni.getSource();
                        Coordinate[] coordinates = {getCoordinateForNode(e.getSource(), geoFields),
                            getCoordinateForNode(e.getTarget(), geoFields)};
                        LineString line = geometryFactory.createLineString(coordinates);
                        featureBuilder.add(line);
                    };
                } else if (name.equals(SIZE_FIELD)) {
                    Float size = (Float) ni.getData(sizeField);
                    size = new Float(size.floatValue() * 0.05); //Scale down as it is a bit large in qgis
                    featureBuilder.add(size);
                } else if (name.equals(COLOR_FIELD)) {
                    String rgb = "";
                    Color color = (Color) ni.getData(colorType);
                    if (color != null) {
                        rgb = Integer.toHexString(color.getRGB());
                        rgb = rgb.substring(2, rgb.length());
                    }
                    featureBuilder.add(rgb);
                } else {
                    featureBuilder.add(row.getValue(name));
                }
            }
            SimpleFeature feature = featureBuilder.buildFeature(null);
            collection.add(feature);
        }
        return collection;
    }

    private void writeSHP(URL url, SimpleFeatureType pointFeatureType, SimpleFeatureCollection pointFeatureSource) throws IOException {

        ShapefileDataStoreFactory dataStoreFactory = new ShapefileDataStoreFactory();

        ShapefileDataStore newDataStore;
        newDataStore = (ShapefileDataStore) dataStoreFactory.createDataStore(url);
        newDataStore.createSchema(pointFeatureType);

        //Write to the Shapefile
        Transaction transaction = new DefaultTransaction("create");

        String typeName = newDataStore.getTypeNames()[0];
        SimpleFeatureSource featureSource = newDataStore.getFeatureSource(typeName);

        if (featureSource instanceof SimpleFeatureStore) {
            SimpleFeatureStore featureStore = (SimpleFeatureStore) featureSource;

            featureStore.setTransaction(transaction);
            try {
                featureStore.addFeatures(pointFeatureSource);
                transaction.commit();

            } catch (Exception problem) {
                Logger.getLogger(SHPExporter.class.getName()).log(Level.SEVERE, null, problem);
                transaction.rollback();

            } finally {
                transaction.close();
            }
        } else {
            Logger.getLogger(SHPExporter.class.getName()).log(Level.SEVERE, null, typeName + " does not support read/write access");
        }
    }

    private Coordinate getCoordinateForNode(Node node, AttributeColumn[] geoFields) {
        double latitude, longitude;

        //is there a location set? else use pseudo-coordinates...
        if (geoFields[0] != null) {
            final AttributeRow row = (AttributeRow) (node).getNodeData().getAttributes();
            latitude = getDoubleForCoordinateFieldObject(row.getValue(geoFields[0]));
            longitude = getDoubleForCoordinateFieldObject(row.getValue(geoFields[1]));
        } else {
            latitude = node.getNodeData().x();
            longitude = node.getNodeData().y();
        }
        Coordinate coordinate = new Coordinate(latitude, longitude);
        return coordinate;
    }
}
