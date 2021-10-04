package cs4224.dao;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.mapper.annotations.CqlName;
import com.datastax.oss.driver.api.mapper.annotations.Dao;
import com.datastax.oss.driver.api.mapper.annotations.Query;
import com.datastax.oss.driver.api.mapper.annotations.Update;
import cs4224.entities.District;

import static com.datastax.oss.driver.api.mapper.entity.saving.NullSavingStrategy.DO_NOT_SET;

@Dao
public interface DistrictDao {

    @Query("SELECT * FROM ${qualifiedTableId} WHERE D_W_ID = :d_w_id AND D_ID = :d_id")
    Row getById(@CqlName("d_w_id") int warehouseId, @CqlName("d_id") int id);

    @Update(customWhereClause = "D_W_ID = :d_w_id AND D_ID = :d_id", nullSavingStrategy = DO_NOT_SET)
    void updateWhereIdEquals(District district, @CqlName("d_w_id") int warehouseId, @CqlName("d_id") int id);

    @Query("SELECT D_NEXT_O_ID FROM ${qualifiedTableId} WHERE D_W_ID = :d_w_id AND D_ID = :d_id")
    Row getNextOrderId(@CqlName("d_w_id") int warehouseId, @CqlName("d_id") int id);
}
