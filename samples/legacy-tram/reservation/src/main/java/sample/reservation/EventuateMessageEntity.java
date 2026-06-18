package sample.reservation;

import javax.persistence.*;

/**
 * Eventuate Tram 'message' 아웃박스 테이블의 JPA 미러(폴백 생성용). init.sql 부재/실패 시 ddl-auto=update가 생성.
 * 컬럼 정의는 핀 버전 Eventuate 공식 스키마와 정확히 일치해야 한다(불일치 시 Eventuate insert 실패).
 */
@Entity
@Table(name = "message")
public class EventuateMessageEntity {
    @Id
    @Column(name = "id", length = 255)
    private String id;                  // init.sql과 동일(255, InnoDB 한계)

    @Column(name = "destination", length = 1000)
    private String destination;

    @Lob
    @Column(name = "headers", columnDefinition = "LONGTEXT")
    private String headers;             // init.sql과 동일

    @Lob
    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "published", columnDefinition = "SMALLINT")
    private Short published;

    @Column(name = "message_partition", columnDefinition = "SMALLINT")
    private Short messagePartition;     // 리뷰 Gemini I1

    @Column(name = "creation_time", columnDefinition = "BIGINT")
    private Long creationTime;

    protected EventuateMessageEntity() {}
}
