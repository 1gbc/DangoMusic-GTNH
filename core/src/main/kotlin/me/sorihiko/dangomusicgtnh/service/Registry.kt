package me.sorihiko.dangomusicgtnh.service

interface Registry<T> {
    fun register(item: T)
    fun unregister(item: T)
}