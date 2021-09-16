/* ****************************************************************
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/
package com.onthegomap.flatmap;

import com.carrotsearch.hppc.IntArrayList;
import com.google.common.primitives.Ints;
import com.google.protobuf.InvalidProtocolBufferException;
import com.onthegomap.flatmap.collection.FeatureGroup;
import com.onthegomap.flatmap.geo.GeoUtils;
import com.onthegomap.flatmap.geo.GeometryException;
import com.onthegomap.flatmap.geo.GeometryType;
import com.onthegomap.flatmap.geo.MutableCoordinateSequence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.concurrent.NotThreadSafe;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Puntal;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vector_tile.VectorTileProto;

/**
 * Encodes a single output tile containing JTS {@link Geometry} features into the compact binary Mapbox Vector Tile
 * format.
 * <p>
 * This class is copied from
 * <a href="https://github.com/ElectronicChartCentre/java-vector-tile/blob/master/src/main/java/no/ecc/vectortile/VectorTileEncoder.java">VectorTileEncoder.java</a>
 * and
 * <a href="https://github.com/ElectronicChartCentre/java-vector-tile/blob/master/src/main/java/no/ecc/vectortile/VectorTileDecoder.java">VectorTileDecoder.java</a>
 * and modified to decouple geometry encoding from vector tile encoding so that encoded commands can be stored in the
 * sorted feature map prior to encoding vector tiles.  The internals are also refactored to improve performance by using
 * hppc primitive collections.
 *
 * @see <a href="https://github.com/mapbox/vector-tile-spec/tree/master/2.1">Mapbox Vector Tile Specification</a>
 */
@NotThreadSafe
public class VectorTile {

  private static final Logger LOGGER = LoggerFactory.getLogger(VectorTile.class);

  // TODO make these configurable
  private static final int EXTENT = 4096;
  private static final double SIZE = 256d;
  private static final double SCALE = ((double) EXTENT) / SIZE;
  private final Map<String, Layer> layers = new LinkedHashMap<>();

  private static int[] getCommands(Geometry input) {
    var encoder = new CommandEncoder();
    encoder.accept(input);
    return encoder.result.toArray();
  }

  private static int zigZagEncode(int n) {
    // https://developers.google.com/protocol-buffers/docs/encoding#types
    return (n << 1) ^ (n >> 31);
  }

  private static int zigZagDecode(int n) {
    // https://developers.google.com/protocol-buffers/docs/encoding#types
    return ((n >> 1) ^ (-(n & 1)));
  }

  private static Geometry decodeCommands(GeometryType geomType, int[] commands) throws GeometryException {
    try {
      GeometryFactory gf = GeoUtils.JTS_FACTORY;
      int x = 0;
      int y = 0;

      List<MutableCoordinateSequence> allCoordSeqs = new ArrayList<>();
      MutableCoordinateSequence currentCoordSeq = null;

      int geometryCount = commands.length;
      int length = 0;
      int command = 0;
      int i = 0;
      while (i < geometryCount) {

        if (length <= 0) {
          length = commands[i++];
          command = length & ((1 << 3) - 1);
          length = length >> 3;
        }

        if (length > 0) {

          if (command == Command.MOVE_TO.value) {
            currentCoordSeq = new MutableCoordinateSequence();
            allCoordSeqs.add(currentCoordSeq);
          } else {
            assert currentCoordSeq != null;
          }

          if (command == Command.CLOSE_PATH.value) {
            if (geomType != GeometryType.POINT && !currentCoordSeq.isEmpty()) {
              currentCoordSeq.closeRing();
            }
            length--;
            continue;
          }

          int dx = commands[i++];
          int dy = commands[i++];

          length--;

          dx = zigZagDecode(dx);
          dy = zigZagDecode(dy);

          x = x + dx;
          y = y + dy;

          currentCoordSeq.forceAddPoint(x / SCALE, y / SCALE);
        }

      }

      Geometry geometry = null;
      boolean outerCCW = false;

      switch (geomType) {
        case LINE -> {
          List<LineString> lineStrings = new ArrayList<>(allCoordSeqs.size());
          for (MutableCoordinateSequence coordSeq : allCoordSeqs) {
            if (coordSeq.size() <= 1) {
              continue;
            }
            lineStrings.add(gf.createLineString(coordSeq));
          }
          if (lineStrings.size() == 1) {
            geometry = lineStrings.get(0);
          } else if (lineStrings.size() > 1) {
            geometry = gf.createMultiLineString(lineStrings.toArray(new LineString[0]));
          }
        }
        case POINT -> {
          CoordinateSequence cs = new PackedCoordinateSequence.Double(allCoordSeqs.size(), 2, 0);
          for (int j = 0; j < allCoordSeqs.size(); j++) {
            MutableCoordinateSequence coordSeq = allCoordSeqs.get(j);
            cs.setOrdinate(j, 0, coordSeq.getX(0));
            cs.setOrdinate(j, 1, coordSeq.getY(0));
          }
          if (cs.size() == 1) {
            geometry = gf.createPoint(cs);
          } else if (cs.size() > 1) {
            geometry = gf.createMultiPoint(cs);
          }
        }
        case POLYGON -> {
          List<List<LinearRing>> polygonRings = new ArrayList<>();
          List<LinearRing> ringsForCurrentPolygon = new ArrayList<>();
          boolean first = true;
          for (MutableCoordinateSequence coordSeq : allCoordSeqs) {
            // skip hole with too few coordinates
            if (ringsForCurrentPolygon.size() > 0 && coordSeq.size() < 2) {
              continue;
            }
            LinearRing ring = gf.createLinearRing(coordSeq);
            boolean ccw = Orientation.isCCW(coordSeq);
            if (first) {
              first = false;
              outerCCW = ccw;
              assert outerCCW : "outer ring is not counter-clockwise";
            }
            if (ccw == outerCCW) {
              ringsForCurrentPolygon = new ArrayList<>();
              polygonRings.add(ringsForCurrentPolygon);
            }
            ringsForCurrentPolygon.add(ring);
          }
          List<Polygon> polygons = new ArrayList<>();
          for (List<LinearRing> rings : polygonRings) {
            LinearRing shell = rings.get(0);
            LinearRing[] holes = rings.subList(1, rings.size()).toArray(new LinearRing[rings.size() - 1]);
            polygons.add(gf.createPolygon(shell, holes));
          }
          if (polygons.size() == 1) {
            geometry = polygons.get(0);
          }
          if (polygons.size() > 1) {
            geometry = gf.createMultiPolygon(GeometryFactory.toPolygonArray(polygons));
          }
        }
        default -> {
        }
      }

      if (geometry == null) {
        geometry = gf.createGeometryCollection(new Geometry[0]);
      }

      return geometry;
    } catch (IllegalArgumentException e) {
      throw new GeometryException("decode_vector_tile", "Unable to decode geometry", e);
    }
  }

  /**
   * Parses a binary-encoded vector tile protobuf into a list of features.
   * <p>
   * Does not decode geometries, but clients can call {@link VectorGeometry#decode()} to decode a JTS {@link Geometry}
   * if needed.
   * <p>
   * If {@code encoded} is compressed, clients must decompress it first.
   *
   * @param encoded encoded vector tile protobuf
   * @return list of features on that tile
   * @throws IllegalStateException     if decoding fails
   * @throws IndexOutOfBoundsException if a tag's key or value refers to an index that does not exist in the keys/values
   *                                   array for a layer
   */
  public static List<Feature> decode(byte[] encoded) {
    try {
      VectorTileProto.Tile tile = VectorTileProto.Tile.parseFrom(encoded);
      List<Feature> features = new ArrayList<>();
      for (VectorTileProto.Tile.Layer layer : tile.getLayersList()) {
        String layerName = layer.getName();
        assert layer.getExtent() == 4096;
        List<String> keys = layer.getKeysList();
        List<Object> values = new ArrayList<>();

        for (VectorTileProto.Tile.Value value : layer.getValuesList()) {
          if (value.hasBoolValue()) {
            values.add(value.getBoolValue());
          } else if (value.hasDoubleValue()) {
            values.add(value.getDoubleValue());
          } else if (value.hasFloatValue()) {
            values.add(value.getFloatValue());
          } else if (value.hasIntValue()) {
            values.add(value.getIntValue());
          } else if (value.hasSintValue()) {
            values.add(value.getSintValue());
          } else if (value.hasUintValue()) {
            values.add(value.getUintValue());
          } else if (value.hasStringValue()) {
            values.add(value.getStringValue());
          } else {
            values.add(null);
          }
        }

        for (VectorTileProto.Tile.Feature feature : layer.getFeaturesList()) {
          int tagsCount = feature.getTagsCount();
          Map<String, Object> attrs = new HashMap<>(tagsCount / 2);
          int tagIdx = 0;
          while (tagIdx < feature.getTagsCount()) {
            String key = keys.get(feature.getTags(tagIdx++));
            Object value = values.get(feature.getTags(tagIdx++));
            attrs.put(key, value);
          }
          features.add(new Feature(
            layerName,
            feature.getId(),
            new VectorGeometry(Ints.toArray(feature.getGeometryList()), GeometryType.valueOf(feature.getType())),
            attrs
          ));
        }
      }
      return features;
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Encodes a JTS geometry according to <a href="https://github.com/mapbox/vector-tile-spec/tree/master/2.1#43-geometry-encoding">Geometry
   * Encoding Specification</a>.
   *
   * @param geometry the JTS geometry to encoded
   * @return the geometry type and command array for the encoded geometry
   */
  public static VectorGeometry encodeGeometry(Geometry geometry) {
    return new VectorGeometry(getCommands(geometry), GeometryType.valueOf(geometry));
  }

  /**
   * Adds features in a layer to this tile.
   *
   * @param layerName name of the layer in this tile to add the features to
   * @param features  features to add to the tile
   * @return this encoder for chaining
   */
  public VectorTile addLayerFeatures(String layerName, List<? extends Feature> features) {
    if (features.isEmpty()) {
      return this;
    }

    Layer layer = layers.get(layerName);
    if (layer == null) {
      layer = new Layer();
      layers.put(layerName, layer);
    }

    for (Feature inFeature : features) {
      if (inFeature != null && inFeature.geometry().commands().length > 0) {
        EncodedFeature outFeature = new EncodedFeature(inFeature);

        for (Map.Entry<String, ?> e : inFeature.attrs().entrySet()) {
          // skip attribute without value
          if (e.getValue() != null) {
            outFeature.tags.add(layer.key(e.getKey()));
            outFeature.tags.add(layer.value(e.getValue()));
          }
        }

        layer.encodedFeatures.add(outFeature);
      }
    }
    return this;
  }

  /**
   * Creates a vector tile protobuf with all features in this tile and serializes it as a byte array.
   * <p>
   * Does not compress the result.
   */
  public byte[] encode() {
    VectorTileProto.Tile.Builder tile = VectorTileProto.Tile.newBuilder();
    for (Map.Entry<String, Layer> e : layers.entrySet()) {
      String layerName = e.getKey();
      Layer layer = e.getValue();

      VectorTileProto.Tile.Layer.Builder tileLayer = VectorTileProto.Tile.Layer.newBuilder()
        .setVersion(2)
        .setName(layerName)
        .setExtent(EXTENT)
        .addAllKeys(layer.keys());

      for (Object value : layer.values()) {
        VectorTileProto.Tile.Value.Builder tileValue = VectorTileProto.Tile.Value.newBuilder();
        if (value instanceof String stringValue) {
          tileValue.setStringValue(stringValue);
        } else if (value instanceof Integer intValue) {
          tileValue.setSintValue(intValue);
        } else if (value instanceof Long longValue) {
          tileValue.setSintValue(longValue);
        } else if (value instanceof Float floatValue) {
          tileValue.setFloatValue(floatValue);
        } else if (value instanceof Double doubleValue) {
          tileValue.setDoubleValue(doubleValue);
        } else if (value instanceof Boolean booleanValue) {
          tileValue.setBoolValue(booleanValue);
        } else {
          tileValue.setStringValue(value.toString());
        }
        tileLayer.addValues(tileValue.build());
      }

      for (EncodedFeature feature : layer.encodedFeatures) {
        VectorTileProto.Tile.Feature.Builder featureBuilder = VectorTileProto.Tile.Feature.newBuilder()
          .addAllTags(Ints.asList(feature.tags.toArray()))
          .setType(feature.geometry().geomType().asProtobufType())
          .addAllGeometry(Ints.asList(feature.geometry().commands()));

        if (feature.id >= 0) {
          featureBuilder.setId(feature.id);
        }

        tileLayer.addFeatures(featureBuilder.build());
      }

      tile.addLayers(tileLayer.build());
    }
    return tile.build().toByteArray();
  }

  private enum Command {
    MOVE_TO(1),
    LINE_TO(2),
    CLOSE_PATH(7);
    final int value;

    Command(int value) {
      this.value = value;
    }
  }

  /**
   * A vector tile encoded as a list of commands according to the <a href="https://github.com/mapbox/vector-tile-spec/tree/master/2.1#43-geometry-encoding">vector
   * tile specification</a>.
   */
  public static record VectorGeometry(int[] commands, GeometryType geomType) {

    /** Converts an encoded geometry back to a JTS geometry. */
    public Geometry decode() throws GeometryException {
      return decodeCommands(geomType, commands);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      VectorGeometry that = (VectorGeometry) o;

      if (geomType != that.geomType) {
        return false;
      }
      return Arrays.equals(commands, that.commands);
    }

    @Override
    public int hashCode() {
      int result = Arrays.hashCode(commands);
      result = 31 * result + geomType.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return "VectorGeometry[" +
        "commands=int[" + commands.length +
        "], geomType=" + geomType +
        " (" + geomType.asByte() + ")]";
    }
  }

  /**
   * A feature in a vector tile.
   *
   * @param layer    the layer the feature was in
   * @param id       the feature ID
   * @param geometry the encoded feature geometry (decode using {@link VectorGeometry#decode()})
   * @param attrs    tags for the feature to output
   * @param group    grouping key used to limit point density or {@link #NO_GROUP} if not in a group. NOTE: this is only
   *                 populated when this feature was deserialized from {@link FeatureGroup}, not when parsed from a tile
   *                 since vector tile schema does not encode group.
   */
  public static record Feature(
    String layer,
    long id,
    VectorGeometry geometry,
    Map<String, Object> attrs,
    long group
  ) {

    public static final long NO_GROUP = Long.MIN_VALUE;

    public Feature(
      String layer,
      long id,
      VectorGeometry geometry,
      Map<String, Object> attrs
    ) {
      this(layer, id, geometry, attrs, NO_GROUP);
    }

    public boolean hasGroup() {
      return group != NO_GROUP;
    }

    /**
     * Encodes {@code newGeometry} and returns a copy of this feature with {@code geometry} replaced with the encoded
     * new geometry.
     */
    public Feature copyWithNewGeometry(Geometry newGeometry) {
      return new Feature(
        layer,
        id,
        encodeGeometry(newGeometry),
        attrs,
        group
      );
    }

    /** Returns a copy of this feature with {@code extraAttrs} added to {@code attrs}. */
    public Feature copyWithExtraAttrs(Map<String, Object> extraAttrs) {
      return new Feature(
        layer,
        id,
        geometry,
        Stream.concat(attrs.entrySet().stream(), extraAttrs.entrySet().stream())
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
        group
      );
    }
  }

  /**
   * Encodes a geometry as a sequence of integers according to the
   * <a href="https://github.com/mapbox/vector-tile-spec/tree/master/2.1#43-geometry-encoding">Geometry   * Encoding
   * Specification</a>.
   */
  private static class CommandEncoder {

    final IntArrayList result = new IntArrayList();
    // Initial points use absolute locations, then subsequent points in a geometry use offsets so
    // need to keep track of previous x/y location during the encoding.
    int x = 0, y = 0;

    static boolean shouldClosePath(Geometry geometry) {
      return (geometry instanceof Polygon) || (geometry instanceof LinearRing);
    }

    static int commandAndLength(Command command, int repeat) {
      return repeat << 3 | command.value;
    }

    void accept(Geometry geometry) {
      if (geometry instanceof MultiLineString multiLineString) {
        for (int i = 0; i < multiLineString.getNumGeometries(); i++) {
          encode(((LineString) multiLineString.getGeometryN(i)).getCoordinateSequence(), false);
        }
      } else if (geometry instanceof Polygon polygon) {
        LineString exteriorRing = polygon.getExteriorRing();
        encode(exteriorRing.getCoordinateSequence(), true);

        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
          LineString interiorRing = polygon.getInteriorRingN(i);
          encode(interiorRing.getCoordinateSequence(), true);
        }
      } else if (geometry instanceof MultiPolygon multiPolygon) {
        for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
          accept(multiPolygon.getGeometryN(i));
        }
      } else if (geometry instanceof LineString lineString) {
        encode(lineString.getCoordinateSequence(), shouldClosePath(geometry));
      } else if (geometry instanceof Point point) {
        encode(point.getCoordinateSequence(), false);
      } else if (geometry instanceof Puntal) {
        encode(new CoordinateArraySequence(geometry.getCoordinates()), shouldClosePath(geometry),
          geometry instanceof MultiPoint);
      } else {
        LOGGER.warn("Unrecognized geometry type: " + geometry.getGeometryType());
      }
    }

    void encode(CoordinateSequence cs, boolean closePathAtEnd) {
      encode(cs, closePathAtEnd, false);
    }

    void encode(CoordinateSequence cs, boolean closePathAtEnd, boolean multiPoint) {

      if (cs.size() == 0) {
        throw new IllegalArgumentException("empty geometry");
      }

      int lineToIndex = 0;
      int lineToLength = 0;

      for (int i = 0; i < cs.size(); i++) {

        double cx = cs.getX(i);
        double cy = cs.getY(i);

        if (i == 0) {
          result.add(commandAndLength(Command.MOVE_TO, multiPoint ? cs.size() : 1));
        }

        int _x = (int) Math.round(cx * SCALE);
        int _y = (int) Math.round(cy * SCALE);

        // prevent point equal to the previous
        if (i > 0 && _x == x && _y == y) {
          lineToLength--;
          continue;
        }

        // prevent double closing
        if (closePathAtEnd && cs.size() > 1 && i == (cs.size() - 1) && cs.getX(0) == cx && cs.getY(0) == cy) {
          lineToLength--;
          continue;
        }

        // delta, then zigzag
        result.add(zigZagEncode(_x - x));
        result.add(zigZagEncode(_y - y));

        x = _x;
        y = _y;

        if (i == 0 && cs.size() > 1 && !multiPoint) {
          // can length be too long?
          lineToIndex = result.size();
          lineToLength = cs.size() - 1;
          result.add(commandAndLength(Command.LINE_TO, lineToLength));
        }

      }

      // update LineTo length
      if (lineToIndex > 0) {
        if (lineToLength == 0) {
          // remove empty LineTo
          result.remove(lineToIndex);
        } else {
          // update LineTo with new length
          result.set(lineToIndex, commandAndLength(Command.LINE_TO, lineToLength));
        }
      }

      if (closePathAtEnd) {
        result.add(commandAndLength(Command.CLOSE_PATH, 1));
      }
    }
  }

  private static final record EncodedFeature(IntArrayList tags, long id, VectorGeometry geometry) {

    EncodedFeature(Feature in) {
      this(new IntArrayList(), in.id(), in.geometry());
    }
  }

  /**
   * Holds all features in an output layer of this tile, along with the index of each tag key/value so that features can
   * store each key/value as a pair of integers.
   */
  private static final class Layer {

    final List<EncodedFeature> encodedFeatures = new ArrayList<>();
    final Map<String, Integer> keys = new LinkedHashMap<>();
    final Map<Object, Integer> values = new LinkedHashMap<>();

    List<String> keys() {
      return new ArrayList<>(keys.keySet());
    }

    List<Object> values() {
      return new ArrayList<>(values.keySet());
    }

    /** Returns the ID associated with {@code key} or adds a new one if not present. */
    Integer key(String key) {
      Integer i = keys.get(key);
      if (i == null) {
        i = keys.size();
        keys.put(key, i);
      }
      return i;
    }

    /** Returns the ID associated with {@code value} or adds a new one if not present. */
    Integer value(Object value) {
      Integer i = values.get(value);
      if (i == null) {
        i = values.size();
        values.put(value, i);
      }
      return i;
    }

    @Override
    public String toString() {
      return "Layer{" + encodedFeatures.size() + "}";
    }
  }
}