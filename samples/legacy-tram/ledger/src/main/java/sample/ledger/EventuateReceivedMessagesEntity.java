package sample.ledger;

import javax.persistence.*;

/** Eventuate Tram 'received_messages'(중복제거) 테이블 JPA 미러(폴백). 핀 버전 스키마와 정확히 일치할 것. */
@Entity @Table(name = "received_messages")
@IdClass(EventuateReceivedMessagesEntity.PK.class)
public class EventuateReceivedMessagesEntity {
    @Id @Column(name = "consumer_id", length = 255) private String consumerId;   // init.sql과 동일(InnoDB 한계)
    @Id @Column(name = "message_id", length = 255) private String messageId;
    @Column(name = "creation_time", columnDefinition = "BIGINT") private Long creationTime;
    @Column(name = "published", columnDefinition = "SMALLINT") private Short published;
    protected EventuateReceivedMessagesEntity() {}
    public static class PK implements java.io.Serializable {
        private String consumerId; private String messageId;
        public PK() {} public PK(String c, String m) { consumerId = c; messageId = m; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK)) return false; PK p = (PK) o;
            return java.util.Objects.equals(consumerId, p.consumerId)
                    && java.util.Objects.equals(messageId, p.messageId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(consumerId, messageId); }
    }
}
