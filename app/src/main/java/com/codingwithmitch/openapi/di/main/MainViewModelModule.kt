package com.codingwithmitch.openapi.di.main

import androidx.lifecycle.ViewModel
import com.codingwithmitch.openapi.di.ViewModelKey
import com.codingwithmitch.openapi.ui.main.account.AccountViewModel
import com.codingwithmitch.openapi.ui.main.blog.viewmodel.BlogViewModel
import com.codingwithmitch.openapi.ui.main.create_blog.CreateBlogViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap

@Module
abstract class MainViewModelModule {

    @Binds
    @IntoMap
    @ViewModelKey(AccountViewModel::class)
    abstract fun bindAuthViewModel(accountViewModel: AccountViewModel) : ViewModel


    @Binds
    @IntoMap
    @ViewModelKey(BlogViewModel::class)
    abstract fun bindBlogViewModel(blogViewModel: BlogViewModel) : ViewModel


    @Binds
    @IntoMap
    @ViewModelKey(CreateBlogViewModel::class)
    abstract fun bindCreateBlogViewModel(createBlogViewModel: CreateBlogViewModel) : ViewModel

}