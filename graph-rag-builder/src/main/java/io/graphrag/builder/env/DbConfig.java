package io.graphrag.builder.env;

/** SUT의 docker-compose에서 추출한 DB 구성. */
public record DbConfig(Type type, String image, String dbName, String user, String password) {

    public enum Type { POSTGRES, MYSQL, MARIADB }
}
