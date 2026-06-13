package io.graphrag.sample.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    long countByUser_Id(String userId);

    List<Order> findByUser_Id(String userId);
}
