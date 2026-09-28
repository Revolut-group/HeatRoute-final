package ru.heatroute;

import java.util.List;
import org.locationtech.jts.geom.Envelope;

interface FeatureStore extends AutoCloseable {
  List<Dataset.Obstacle> query(Envelope envelope);

  boolean containsId(String id);

  @Override
  void close();
}
