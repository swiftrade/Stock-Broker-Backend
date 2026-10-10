package com.stockpredict.paper.data;

/** Persistence for {@code fills}. */
public interface FillRepository {

    void insert(FillRow fill);
}
