package com.elvitalya.kotlintonometer

import androidx.lifecycle.MutableLiveData

class TonometerRepository {


    private var instance: TonometerRepository? = null

    fun get(): TonometerRepository {
        if (instance == null) instance = TonometerRepository()
        return instance as TonometerRepository
    }
     val data = MutableLiveData<TonometerData>()
}



data class TonometerData(
    val one: String,
    val two: String,
    val three: String
)