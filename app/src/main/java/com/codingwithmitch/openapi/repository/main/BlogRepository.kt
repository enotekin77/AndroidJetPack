package com.codingwithmitch.openapi.repository.main

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.switchMap
import com.codingwithmitch.openapi.api.GenericResponse
import com.codingwithmitch.openapi.api.main.OpenApiMainService
import com.codingwithmitch.openapi.api.main.responses.BlogCreateUpdateResponse
import com.codingwithmitch.openapi.api.main.responses.BlogListSearchResponse
import com.codingwithmitch.openapi.models.AuthToken
import com.codingwithmitch.openapi.models.BlogPost
import com.codingwithmitch.openapi.persistence.BlogPostDao
import com.codingwithmitch.openapi.persistence.returnOrderedBlogQuery
import com.codingwithmitch.openapi.repository.JobManager
import com.codingwithmitch.openapi.repository.NetworkBoundResource
import com.codingwithmitch.openapi.session.SessionManager
import com.codingwithmitch.openapi.ui.DataState
import com.codingwithmitch.openapi.ui.Response
import com.codingwithmitch.openapi.ui.ResponseType
import com.codingwithmitch.openapi.ui.main.blog.state.BlogViewState
import com.codingwithmitch.openapi.util.*
import com.codingwithmitch.openapi.util.ErrorHandling.Companion.ERROR_UNKNOWN
import com.codingwithmitch.openapi.util.SuccessHandling.Companion.RESPONSE_HAS_PERMISSION_TO_EDIT
import com.codingwithmitch.openapi.util.SuccessHandling.Companion.SUCCESS_BLOG_DELETED
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.lang.Exception
import javax.inject.Inject

class BlogRepository
@Inject
constructor(
    val openApiMainService: OpenApiMainService,
    val blogPostDao: BlogPostDao,
    val sessionManager: SessionManager
): JobManager("BlogRepository")
{
    private val TAG = "AppDebug"

    fun searchBlogPosts(
        authToken: AuthToken,
        query: String,
        filterAndOrder: String,
        page: Int
    ): LiveData<DataState<BlogViewState>> {

        return object: NetworkBoundResource<BlogListSearchResponse,List<BlogPost>,BlogViewState>(
            sessionManager.isConnectedToTheInternet(),
            true,
            false,
            true
        ){

            // if network is down, view cache only and return
            override suspend fun createCacheRequestAndReturn() {
                withContext(Main) {

                    // finishing by viewing db cache

                    result.addSource(loadFromCache()) { viewState ->
                        viewState.blogFields.isQueryInProgress = false
                        if (page * Constants.PAGINATION_PAGE_SIZE > viewState.blogFields.blogList.size) {
                            viewState.blogFields.isQueryExhausted = true
                        }
                        onCompleteJob(DataState.data(viewState,null))
                    }
                }

            }

            override suspend fun handleApiSuccessResponse(response: ApiSuccessResponse<BlogListSearchResponse>) {

            val blogPostList: ArrayList<BlogPost> = ArrayList()
                for (blogPostResponse in response.body.results) {
                    blogPostList.add(
                        BlogPost(
                            pk = blogPostResponse.pk,
                            title = blogPostResponse.title,
                            slug = blogPostResponse.slug,
                            body = blogPostResponse.body,
                            image = blogPostResponse.image,
                            date_updated = DateUtils.convertServerStringDateToLong(
                                blogPostResponse.date_updated
                            ),
                            username = blogPostResponse.username
                        )
                    )
                }
                updateLocalDb(blogPostList)

                createCacheRequestAndReturn()
            }

            override fun createCall(): LiveData<GenericApiResponse<BlogListSearchResponse>> {
               return openApiMainService.searchListBlogPosts(
                   "Token ${authToken.token!!}",
                   query = query,
                   ordering = filterAndOrder,
                   page = page
               )
            }

            override fun loadFromCache(): LiveData<BlogViewState> {

                return  blogPostDao.returnOrderedBlogQuery(
                    query = query,
                    filterAndOrder = filterAndOrder,
                    page = page
                )
                    .switchMap {
                        object : LiveData<BlogViewState>() {
                            override fun onActive() {
                                super.onActive()
                                value = BlogViewState(
                                    BlogViewState.BlogFields(
                                        blogList = it,
                                        isQueryInProgress = true
                                    )
                                )
                            }
                        }
                    }

            }

            override suspend fun updateLocalDb(cacheObject: List<BlogPost>?) {
                // loop through list and update the local db
               if (cacheObject != null) {
                   withContext(IO) {
                       for (blogPost in cacheObject) {
                           try {
                               // launch each insert as a separate job to executed in parallel
                               launch {
                                   Log.d(TAG,"updateLocalDb: inserting blog: $blogPost")
                                   blogPostDao.insert(blogPost)
                               }

                           }catch (e: Exception) {
                               Log.e(TAG,"updateLocalDb: error updating cache" +
                               "on blog post with slug: ${blogPost.slug}")
                               // optional error handling??
                           }
                       }
                   }
               }
            }

            override fun setJob(job: Job) {
                addJob("searchBlogPosts",job)
            }

        }.asLiveData()

    }

    fun isAuthorOfBlogPost(
        authToken: AuthToken,
        slug:String
    ): LiveData<DataState<BlogViewState>> {
        return object: NetworkBoundResource<GenericResponse,Any,BlogViewState>(
            sessionManager.isConnectedToTheInternet(),
          true,
            true,
            false

        ) {

            //not applicable
            override suspend fun createCacheRequestAndReturn() {

            }

            override suspend fun handleApiSuccessResponse(response: ApiSuccessResponse<GenericResponse>) {
                withContext(Main){
                    Log.d(TAG,"handleApiSuccessResponse: ${response.body.response}")

                     var isAuthor = false
                    if (response.body.response.equals(RESPONSE_HAS_PERMISSION_TO_EDIT)){

                        isAuthor = true

                    }
                    onCompleteJob(
                        DataState.data(
                            data = BlogViewState(
                                viewBlogFields = BlogViewState.ViewBlogFields(
                                    isAuthorOfBlogPost = isAuthor
                                )
                            ),
                            response = null
                        )
                    )

                }
            }

            override fun createCall(): LiveData<GenericApiResponse<GenericResponse>> {
               return openApiMainService.isAuthorOfBlogPost(
                   "Token ${authToken.token}",
                   slug
               )
            }
            //not applicable
            override fun loadFromCache(): LiveData<BlogViewState> {
                return AbsentLiveData.create()
            }

            //not applicable
            override suspend fun updateLocalDb(cacheObject: Any?) {
            }

            override fun setJob(job: Job) {
                addJob("isAuthorOfBlogPost",job)
            }

        }.asLiveData()
    }

    fun deleteBlogPost(
        authToken: AuthToken,
        blogPost: BlogPost
    ): LiveData<DataState<BlogViewState>>{
        return object : NetworkBoundResource<GenericResponse,BlogPost,BlogViewState>(
            sessionManager.isConnectedToTheInternet(),
            true,
            true,
            false
        ){


            // not applicable
            override suspend fun createCacheRequestAndReturn() {


            }

            override suspend fun handleApiSuccessResponse(response: ApiSuccessResponse<GenericResponse>) {
                if (response.body.response == SUCCESS_BLOG_DELETED) {
                    updateLocalDb(blogPost)
                }else {
                    onCompleteJob(
                        DataState.error(
                            Response(
                                ERROR_UNKNOWN,
                                ResponseType.Dialog()
                            )
                        )
                    )
                }
            }

            override fun createCall(): LiveData<GenericApiResponse<GenericResponse>> {
                return  openApiMainService.deleteBlogPost(
                    "Token ${authToken.token!!}",
                    blogPost.slug
                )
            }


            //not applicable
            override fun loadFromCache(): LiveData<BlogViewState> {
               return AbsentLiveData.create()
            }

            override suspend fun updateLocalDb(cacheObject: BlogPost?) {
                cacheObject?.let {blogPost ->
                    blogPostDao.deleteBlogPost(blogPost)
                    onCompleteJob(
                        DataState.data(
                            data = null,
                            response = Response(SUCCESS_BLOG_DELETED,ResponseType.Toast())
                        )
                    )

                }
            }

            override fun setJob(job: Job) {
                addJob("deleteBlogPost",job)
            }

        }.asLiveData()
    }

    fun updateBlogPost(
        authToken: AuthToken,
        slug: String,
        title: RequestBody,
        body: RequestBody,
        image: MultipartBody.Part?
    ): LiveData<DataState<BlogViewState>> {
        return object : NetworkBoundResource<BlogCreateUpdateResponse, BlogPost, BlogViewState>(
            sessionManager.isConnectedToTheInternet(),
            true,
            true,
            false
        ) {

            // not applicable
            override suspend fun createCacheRequestAndReturn() {

            }

            override suspend fun handleApiSuccessResponse(response: ApiSuccessResponse<BlogCreateUpdateResponse>) {
                val updatedBlogPost = BlogPost(
                    response.body.pk,
                    response.body.title,
                    response.body.slug,
                    response.body.body,
                    response.body.image,
                    DateUtils.convertServerStringDateToLong(
                        response.body.date_updated
                    ),
                    response.body.username

                )

                updateLocalDb(updatedBlogPost)

                withContext(Main) {
                    onCompleteJob(
                        DataState.data(
                            data = BlogViewState(
                                viewBlogFields = BlogViewState.ViewBlogFields(
                                    blogPost = updatedBlogPost
                                )
                            ),
                            response = Response(response.body.response, ResponseType.Toast())
                        )
                    )
                }
            }

            override fun createCall(): LiveData<GenericApiResponse<BlogCreateUpdateResponse>> {
               return  openApiMainService.updateBlog(
                   "Token ${authToken.token}",
                   slug,
                   title,
                   body,
                   image
               )
            }

            override fun loadFromCache(): LiveData<BlogViewState> {

                return AbsentLiveData.create()
            }

            override suspend fun updateLocalDb(cacheObject: BlogPost?) {
               cacheObject?.let { blogPost ->
                   blogPostDao.updateBlogPost(
                       blogPost.pk,
                       blogPost.title,
                       blogPost.body,
                       blogPost.image
                   )
               }
            }

            override fun setJob(job: Job) {
              addJob("updateBlogPost", job)
            }

        }.asLiveData()
    }
}