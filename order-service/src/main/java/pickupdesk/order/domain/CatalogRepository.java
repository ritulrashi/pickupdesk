package pickupdesk.order.domain;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private record Price(String sku, long unitPriceCents) {
    }

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return sku -> unit price in cents, for the skus that exist */
    public Map<String, Long> prices(Collection<String> skus) {
        return jdbc.sql("SELECT sku, unit_price_cents FROM catalog WHERE sku IN (:skus)")
                .param("skus", skus)
                .query(Price.class)
                .list()
                .stream()
                .collect(Collectors.toMap(Price::sku, Price::unitPriceCents));
    }

    public void upsert(String sku, String name, long unitPriceCents) {
        jdbc.sql("""
                INSERT INTO catalog (sku, name, unit_price_cents) VALUES (:sku, :name, :price)
                ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, unit_price_cents = EXCLUDED.unit_price_cents
                """)
                .param("sku", sku).param("name", name).param("price", unitPriceCents)
                .update();
    }
}
