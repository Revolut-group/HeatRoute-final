package ru.heatroute;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("jobs")
public class Job {
  @Id public String id;
  @Version public Long version;
  public String state, stage, errorCode, errorMessage;
  public long receivedBytes;
  public String createdAt;

  public Job() {}

  public Job(String id) {
    this.id = id;
    state = "RECEIVING";
    stage = "RECEIVING";
    createdAt = java.time.Instant.now().toString();
  }
}
