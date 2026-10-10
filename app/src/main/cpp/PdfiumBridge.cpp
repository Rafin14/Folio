#include <jni.h>
#include <dlfcn.h>
#include <mutex>
#include <vector>
#include <sstream>
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <fstream>
#include "pdfium/fpdfview.h"
#include "pdfium/fpdf_text.h"
#include "pdfium/fpdf_edit.h"
#include "pdfium/fpdf_transformpage.h"
#include "pdfium/fpdf_ppo.h"
#include "pdfium/fpdf_save.h"
#include "pdfium/fpdf_annot.h"

namespace {
std::mutex lock;
void* library=nullptr;
template<class T> T symbol(const char* name) {
    void* value=dlsym(library,name);
    if(!value) throw std::runtime_error(std::string("PDFium API unavailable: ")+name);
    return reinterpret_cast<T>(value);
}
#define API(name) symbol<decltype(&name)>(#name)
void init() {
    if(library) return;
    library=dlopen("libpdfium.so",RTLD_NOW|RTLD_LOCAL);
    if(!library) throw std::runtime_error("PDFium could not load");
    API(FPDF_InitLibrary)();
}
void fail(JNIEnv* env,const std::exception& error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what()); }
void quoted(std::ostream& out,const char* value) {out<<'"';for(const unsigned char* c=reinterpret_cast<const unsigned char*>(value);*c;c++) {if(*c=='"'||*c=='\\') out<<'\\';if(*c>=32) out<<static_cast<char>(*c);}out<<'"';}
bool opaqueImage(FPDF_DOCUMENT document,FPDF_PAGE page,FPDF_PAGEOBJECT object,const FS_MATRIX& matrix) {
    unsigned int w=0,h=0;
    if(!API(FPDFImageObj_GetImagePixelSize)(object,&w,&h) || !w || !h || static_cast<unsigned long long>(w)*h>16000000 || std::abs(matrix.a*matrix.d)>16000000) return false;
    auto bitmap=API(FPDFImageObj_GetRenderedBitmap)(document,page,object);
    if(!bitmap) return false;
    bool opaque=true;
    if(API(FPDFBitmap_GetFormat)(bitmap)==FPDFBitmap_BGRA) {
        auto data=static_cast<unsigned char*>(API(FPDFBitmap_GetBuffer)(bitmap));const int stride=API(FPDFBitmap_GetStride)(bitmap);
        for(int y=0;y<API(FPDFBitmap_GetHeight)(bitmap)&&opaque;y++) for(int x=0;x<API(FPDFBitmap_GetWidth)(bitmap);x++) if(data[y*stride+x*4+3]!=255) {opaque=false;break;}
    }
    API(FPDFBitmap_Destroy)(bitmap);return opaque;
}
struct Page {
    FPDF_PAGE value;
    explicit Page(jlong handle,int index):value(API(FPDF_LoadPage)(reinterpret_cast<FPDF_DOCUMENT>(handle),index)) { if(!value) throw std::runtime_error("PDF page could not load"); }
    ~Page() { API(FPDF_ClosePage)(value); }
};
void box(std::ostream& json,FPDF_PAGE p,int width,int height,double left,double bottom,double right,double top) {
    int x[4],y[4];double xx[]={left,right,right,left}, yy[]={bottom,bottom,top,top};
    for(int i=0;i<4;i++) if(!API(FPDF_PageToDevice)(p,0,0,width,height,0,xx[i],yy[i],&x[i],&y[i])) throw std::runtime_error("PDF coordinate mapping failed");
    json<<'['<<*std::min_element(x,x+4)<<','<<*std::min_element(y,y+4)<<','<<*std::max_element(x,x+4)<<','<<*std::max_element(y,y+4)<<']';
}
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_open(JNIEnv* env,jobject,jstring path) {
    std::lock_guard<std::mutex> guard(lock);
    try { init();const char* file=env->GetStringUTFChars(path,nullptr);auto doc=API(FPDF_LoadDocument)(file,nullptr);env->ReleaseStringUTFChars(path,file);
        if(!doc) throw std::runtime_error("PDFium could not open this PDF");return reinterpret_cast<jlong>(doc);
    } catch(const std::exception& e) {fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_close(JNIEnv*,jobject,jlong doc) {
    std::lock_guard<std::mutex> guard(lock);if(doc) API(FPDF_CloseDocument)(reinterpret_cast<FPDF_DOCUMENT>(doc));
}
extern "C" JNIEXPORT jint JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_count(JNIEnv* env,jobject,jlong doc) {
    std::lock_guard<std::mutex> guard(lock);try {return API(FPDF_GetPageCount)(reinterpret_cast<FPDF_DOCUMENT>(doc));} catch(const std::exception& e) {fail(env,e);return 0;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_size(JNIEnv* env,jobject,jlong doc,jint index) {
    std::lock_guard<std::mutex> guard(lock);try {Page page(doc,index);double values[]={API(FPDF_GetPageWidth)(page.value),API(FPDF_GetPageHeight)(page.value)};
        auto result=env->NewDoubleArray(2);env->SetDoubleArrayRegion(result,0,2,values);return result;
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jintArray JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_render(JNIEnv* env,jobject,jlong doc,jint index,jint w,jint h) {
    std::lock_guard<std::mutex> guard(lock);try {
        if(w<=0||h<=0||static_cast<long long>(w)*h>16000000) throw std::runtime_error("PDF render exceeds memory limit");
        Page page(doc,index);std::vector<jint> pixels(static_cast<size_t>(w)*h,static_cast<jint>(0xffffffff));
        auto bitmap=API(FPDFBitmap_CreateEx)(w,h,FPDFBitmap_BGRA,pixels.data(),w*4);
        if(!bitmap) throw std::runtime_error("PDF bitmap allocation failed");
        API(FPDF_RenderPageBitmap)(bitmap,page.value,0,0,w,h,0,FPDF_ANNOT);
        API(FPDFBitmap_Destroy)(bitmap);
        auto result=env->NewIntArray(w*h);if(result) env->SetIntArrayRegion(result,0,w*h,pixels.data());return result;
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_text(JNIEnv* env,jobject,jlong doc,jint index) {
    std::lock_guard<std::mutex> guard(lock);try {Page page(doc,index);auto text=API(FPDFText_LoadPage)(page.value);if(!text) throw std::runtime_error("PDF text unavailable");
        const int count=API(FPDFText_CountChars)(text);
        if(count<0||count>100000) {API(FPDFText_ClosePage)(text);throw std::runtime_error("PDF text exceeds supported limit");}
        std::vector<unsigned short> value(count+1);int length=API(FPDFText_GetText)(text,0,count,value.data());API(FPDFText_ClosePage)(text);
        return env->NewString(reinterpret_cast<jchar*>(value.data()),std::max(0,length-1));
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_inspect(JNIEnv* env,jobject,jlong doc,jint index,jint w,jint h) {
    std::lock_guard<std::mutex> guard(lock);try {
        Page page(doc,index);std::ostringstream out;out<<"{\"objects\":[";
        int count=API(FPDFPage_CountObjects)(page.value);if(count>20000) throw std::runtime_error("PDF page has too many objects");
        bool comma=false;
        for(int i=0;i<count;i++) {
            auto object=API(FPDFPage_GetObject)(page.value,i);float l,b,r,t;
            if(!API(FPDFPageObj_GetBounds)(object,&l,&b,&r,&t)||!std::isfinite(l+b+r+t)) continue;
            if(comma) out<<',';comma=true;
            const int type=API(FPDFPageObj_GetType)(object);FS_MATRIX matrix{};API(FPDFPageObj_GetMatrix)(object,&matrix);
            out<<"{\"index\":"<<i<<",\"type\":"<<type<<",\"box\":";box(out,page.value,w,h,l,b,r,t);
            out<<",\"pdfBox\":["<<l<<','<<b<<','<<r<<','<<t<<"],\"matrix\":["<<matrix.a<<','<<matrix.b<<','<<matrix.c<<','<<matrix.d<<','<<matrix.e<<','<<matrix.f<<']';
            auto clip=API(FPDFPageObj_GetClipPath)(object);
            bool clipped=clip && API(FPDFClipPath_CountPaths)(clip)>0;
            bool jpeg=false;
            if(type==FPDF_PAGEOBJ_IMAGE && API(FPDFImageObj_GetImageFilterCount)(object)==1) {char filter[64]{};API(FPDFImageObj_GetImageFilter)(object,0,filter,sizeof(filter));jpeg=std::string(filter)=="DCTDecode";}
            bool original=jpeg&&!clipped&&matrix.a>0&&matrix.d>0&&std::abs(matrix.b)<.001&&std::abs(matrix.c)<.001&&opaqueImage(reinterpret_cast<FPDF_DOCUMENT>(doc),page.value,object,matrix);
            out<<",\"clipped\":"<<(clipped?"true":"false")<<",\"originalJpeg\":"<<(original?"true":"false");
            out<<",\"segments\":[";
            if(type==FPDF_PAGEOBJ_PATH) {
                int segments=std::min(10000,API(FPDFPath_CountSegments)(object));
                for(int s=0;s<segments;s++) {auto segment=API(FPDFPath_GetPathSegment)(object,s);float x,y;API(FPDFPathSegment_GetPoint)(segment,&x,&y);
                    const double px=matrix.a*x+matrix.c*y+matrix.e,py=matrix.b*x+matrix.d*y+matrix.f;int dx,dy;API(FPDF_PageToDevice)(page.value,0,0,w,h,0,px,py,&dx,&dy);
                    if(s) out<<',';out<<'['<<dx<<','<<dy<<','<<API(FPDFPathSegment_GetType)(segment)<<']';}
            }
            out<<"]}";
        }
        out<<"],\"characters\":[";auto text=API(FPDFText_LoadPage)(page.value);
        if(text) {int n=API(FPDFText_CountChars)(text);if(n>100000) {API(FPDFText_ClosePage)(text);throw std::runtime_error("PDF text exceeds supported limit");}
            for(int i=0;i<n;i++) {double l,r,b,t;API(FPDFText_GetCharBox)(text,i,&l,&r,&b,&t);int flags=0;char font[256]{};API(FPDFText_GetFontInfo)(text,i,font,sizeof(font),&flags);
                if(i) out<<',';out<<"{\"unicode\":"<<API(FPDFText_GetUnicode)(text,i)<<",\"box\":";box(out,page.value,w,h,l,b,r,t);
                out<<",\"size\":"<<API(FPDFText_GetFontSize)(text,i)<<",\"flags\":"<<flags<<",\"font\":";quoted(out,font);out<<",\"bold\":"<<(API(FPDFText_GetFontWeight)(text,i)>=600?"true":"false")<<'}';
            }API(FPDFText_ClosePage)(text);
        }
        out<<"],\"annotations\":"<<API(FPDFPage_GetAnnotCount)(page.value)<<'}';return env->NewStringUTF(out.str().c_str());
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_originalJpeg(JNIEnv* env,jobject,jlong doc,jint index,jint objectIndex,jstring target) {
    std::lock_guard<std::mutex> guard(lock);try {Page page(doc,index);int n=API(FPDFPage_CountObjects)(page.value);if(objectIndex<0||objectIndex>=n) throw std::runtime_error("Invalid PDF object");
        auto object=API(FPDFPage_GetObject)(page.value,objectIndex);if(API(FPDFPageObj_GetType)(object)!=FPDF_PAGEOBJ_IMAGE) throw std::runtime_error("Not a PDF image");
        if(API(FPDFImageObj_GetImageFilterCount)(object)!=1) throw std::runtime_error("Image cannot be exported as original JPEG");
        char filter[64]{};API(FPDFImageObj_GetImageFilter)(object,0,filter,sizeof(filter));if(std::string(filter)!="DCTDecode") throw std::runtime_error("Image is not JPEG encoded");
        unsigned long length=API(FPDFImageObj_GetImageDataRaw)(object,nullptr,0);if(!length||length>100*1024*1024) throw std::runtime_error("PDF image exceeds extraction limit");
        std::vector<unsigned char> data(length);API(FPDFImageObj_GetImageDataRaw)(object,data.data(),length);
        const char* path=env->GetStringUTFChars(target,nullptr);std::ofstream output(path,std::ios::binary);env->ReleaseStringUTFChars(target,path);output.write(reinterpret_cast<char*>(data.data()),length);
        if(!output) throw std::runtime_error("Image export could not be written");
    } catch(const std::exception& e) {fail(env,e);}
}
namespace {
struct Writer:FPDF_FILEWRITE {
    std::ofstream output;
    explicit Writer(const char* file):output(file,std::ios::binary) {version=1;WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long length)->int {auto* self=static_cast<Writer*>(base);self->output.write(static_cast<const char*>(data),length);return self->output.good();};}
};
}
extern "C" JNIEXPORT void JNICALL Java_dev_folio_scanner_pdfanalysis_PdfiumNative_vectorRegion(JNIEnv* env,jobject,jlong doc,jint index,jint w,jint h,jfloat left,jfloat top,jfloat right,jfloat bottom,jstring target) {
    std::lock_guard<std::mutex> guard(lock);FPDF_DOCUMENT output=nullptr;
    try {
        Page original(doc,index);double x[4],y[4];int xx[]={static_cast<int>(left),static_cast<int>(right),static_cast<int>(right),static_cast<int>(left)},yy[]={static_cast<int>(top),static_cast<int>(top),static_cast<int>(bottom),static_cast<int>(bottom)};
        for(int i=0;i<4;i++) if(!API(FPDF_DeviceToPage)(original.value,0,0,w,h,0,xx[i],yy[i],&x[i],&y[i])) throw std::runtime_error("PDF coordinate mapping failed");
        double l=*std::min_element(x,x+4),r=*std::max_element(x,x+4),b=*std::min_element(y,y+4),t=*std::max_element(y,y+4);
        if(r<=l||t<=b) throw std::runtime_error("Invalid vector region");
        output=API(FPDF_CreateNewDocument)();if(!output) throw std::runtime_error("PDF allocation failed");
        const std::string range=std::to_string(index+1);
        if(!API(FPDF_ImportPages)(output,reinterpret_cast<FPDF_DOCUMENT>(doc),range.c_str(),0)) throw std::runtime_error("Vector page copy failed");
        {
            Page page(reinterpret_cast<jlong>(output),0);
            if(API(FPDFPage_GetAnnotCount)(page.value)>0) throw std::runtime_error("Annotated figure requires rendered export");
            int kept=0;
            for(int i=API(FPDFPage_CountObjects)(page.value)-1;i>=0;i--) {
                auto object=API(FPDFPage_GetObject)(page.value,i);float ol,ob,orr,ot;
                if(!API(FPDFPageObj_GetBounds)(object,&ol,&ob,&orr,&ot)) throw std::runtime_error("Unknown vector object bounds");
                if(orr<l||ol>r||ot<b||ob>t) {if(!API(FPDFPage_RemoveObject)(page.value,object)) throw std::runtime_error("PDF object removal failed");API(FPDFPageObj_Destroy)(object);continue;}
                int type=API(FPDFPageObj_GetType)(object);auto clip=API(FPDFPageObj_GetClipPath)(object);
                if((type!=FPDF_PAGEOBJ_TEXT&&type!=FPDF_PAGEOBJ_PATH)||ol<l-.75||orr>r+.75||ob<b-.75||ot>t+.75||(clip&&API(FPDFClipPath_CountPaths)(clip)>0)) throw std::runtime_error("Composite or clipped figure requires rendered export");
                API(FPDFPageObj_Transform)(object,1,0,0,1,-l,-b);kept++;
            }
            if(!kept) throw std::runtime_error("No vector objects in region");
            API(FPDFPage_SetMediaBox)(page.value,0,0,r-l,t-b);API(FPDFPage_SetCropBox)(page.value,0,0,r-l,t-b);
            if(!API(FPDFPage_GenerateContent)(page.value)) throw std::runtime_error("Vector content generation failed");
            const char* path=env->GetStringUTFChars(target,nullptr);Writer writer(path);env->ReleaseStringUTFChars(target,path);
            if(!API(FPDF_SaveAsCopy)(output,&writer,FPDF_NO_INCREMENTAL)) throw std::runtime_error("Vector PDF export failed");
        }
        API(FPDF_CloseDocument)(output);output=nullptr;
    } catch(const std::exception& e) {if(output) API(FPDF_CloseDocument)(output);fail(env,e);}
}
