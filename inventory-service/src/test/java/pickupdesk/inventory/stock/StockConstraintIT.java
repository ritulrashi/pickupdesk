package pickupdesk.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import pickupdesk.inventory.InventoryITBase;

/** The CHECK constraints hold even when application code is bypassed entirely. */
class StockConstraintIT extends InventoryITBase {

    private void rawUpdate(String set, String store, String sku) {
        jdbc.sql("UPDATE stock SET " + set + " WHERE store_id = :store AND sku = :sku")
                .param("store", store)
                .param("sku", sku)
                .update();
    }

    @Test
    void databaseRejectsReservedAboveOnHand() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 5);
        assertThatThrownBy(() -> rawUpdate("reserved = 6", store, sku))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("stock_reserved_le_on_hand");
        assertThat(stock(store, sku).reserved()).isZero();
    }

    @Test
    void databaseRejectsNegativeCounts() {
        String store = newStore(), sku = newSku();
        seed(store, sku, 5);
        assertThatThrownBy(() -> rawUpdate("reserved = -1", store, sku))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("stock_reserved_nonneg");
        assertThatThrownBy(() -> rawUpdate("on_hand = -1", store, sku))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unguardedIncrementPastStockIsStoppedByTheConstraint() {
        // What a buggy reserve without the "on_hand - reserved >= qty" guard would run.
        String store = newStore(), sku = newSku();
        seed(store, sku, 1);
        rawUpdate("reserved = reserved + 1", store, sku);
        assertThatThrownBy(() -> rawUpdate("reserved = reserved + 1", store, sku))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stock(store, sku).reserved()).isEqualTo(1);
    }
}
