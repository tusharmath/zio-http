"use strict";(self.webpackChunkzio_http_docs=self.webpackChunkzio_http_docs||[]).push([[270],{3905:function(e,t,n){n.d(t,{Zo:function(){return d},kt:function(){return f}});var r=n(7294);function a(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function i(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);t&&(r=r.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,r)}return n}function o(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?i(Object(n),!0).forEach((function(t){a(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):i(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function c(e,t){if(null==e)return{};var n,r,a=function(e,t){if(null==e)return{};var n,r,a={},i=Object.keys(e);for(r=0;r<i.length;r++)n=i[r],t.indexOf(n)>=0||(a[n]=e[n]);return a}(e,t);if(Object.getOwnPropertySymbols){var i=Object.getOwnPropertySymbols(e);for(r=0;r<i.length;r++)n=i[r],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(a[n]=e[n])}return a}var s=r.createContext({}),p=function(e){var t=r.useContext(s),n=t;return e&&(n="function"==typeof e?e(t):o(o({},t),e)),n},d=function(e){var t=p(e.components);return r.createElement(s.Provider,{value:t},e.children)},l={inlineCode:"code",wrapper:function(e){var t=e.children;return r.createElement(r.Fragment,{},t)}},u=r.forwardRef((function(e,t){var n=e.components,a=e.mdxType,i=e.originalType,s=e.parentName,d=c(e,["components","mdxType","originalType","parentName"]),u=p(n),f=a,h=u["".concat(s,".").concat(f)]||u[f]||l[f]||i;return n?r.createElement(h,o(o({ref:t},d),{},{components:n})):r.createElement(h,o({ref:t},d))}));function f(e,t){var n=arguments,a=t&&t.mdxType;if("string"==typeof e||a){var i=n.length,o=new Array(i);o[0]=u;var c={};for(var s in t)hasOwnProperty.call(t,s)&&(c[s]=t[s]);c.originalType=e,c.mdxType="string"==typeof e?e:a,o[1]=c;for(var p=2;p<i;p++)o[p]=n[p];return r.createElement.apply(null,o)}return r.createElement.apply(null,n)}u.displayName="MDXCreateElement"},6717:function(e,t,n){n.r(t),n.d(t,{frontMatter:function(){return c},contentTitle:function(){return s},metadata:function(){return p},toc:function(){return d},default:function(){return u}});var r=n(7462),a=n(3366),i=(n(7294),n(3905)),o=["components"],c={},s="Sticky Threads",p={unversionedId:"advanced-examples/sticky-threads",id:"advanced-examples/sticky-threads",isDocsHomePage:!1,title:"Sticky Threads",description:"",source:"@site/docs/advanced-examples/sticky-threads.md",sourceDirName:"advanced-examples",slug:"/advanced-examples/sticky-threads",permalink:"/zio-http/docs/advanced-examples/sticky-threads",tags:[],version:"current",frontMatter:{},sidebar:"tutorialSidebar",previous:{title:"Advanced Server",permalink:"/zio-http/docs/advanced-examples/hello-world-advanced"},next:{title:"Streaming File",permalink:"/zio-http/docs/advanced-examples/stream-file"}},d=[],l={toc:d};function u(e){var t=e.components,n=(0,a.Z)(e,o);return(0,i.kt)("wrapper",(0,r.Z)({},l,n,{components:t,mdxType:"MDXLayout"}),(0,i.kt)("h1",{id:"sticky-threads"},"Sticky Threads"),(0,i.kt)("pre",null,(0,i.kt)("code",{parentName:"pre",className:"language-scala"},'import zhttp.http._\nimport zhttp.service.Server\nimport zio._\nimport zio.duration._\n\n/**\n * The following example depicts thread stickiness. The way it works is \u2014 once a \n * request is received on the server, a thread is associated with it permanently.\n * Any ZIO execution within the context of that request is guaranteed to be done\n * on the same thread. This level of thread stickiness improves the performance \n * characteristics of the server dramatically.\n */\nobject StickyThread extends App {\n\n  /**\n   * A simple utility function that prints the fiber with the current thread.\n   */\n  private def printThread(tag: String): ZIO[Any, Nothing, Unit] = {\n    for {\n      id <- ZIO.fiberId\n      _  <- UIO(println(s"${tag.padTo(6, \' \')}:\n         Fiber(${id.seqNumber}) Thread(${Thread.currentThread().getName})"))\n    } yield ()\n  }\n\n  /**\n   * The expected behaviour is that all the `printThread` output different fiber ids\n   * with the same thread name.\n   */\n  val app = HttpApp.collectM { case Method.GET -> !! / "text" =>\n    for {\n\n      _  <- printThread("Start")\n      f1 <- ZIO.sleep(1 second).zipLeft(printThread("First")).fork\n      f2 <- ZIO.sleep(1 second).zipLeft(printThread("Second")).fork\n      _  <- f1.join <*> f2.join\n    } yield Response.text("Hello World!")\n  }\n\n  // Run it like any simple app\n  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =\n    Server.start(8090, app.silent).exitCode\n}\n\n')))}u.isMDXComponent=!0}}]);