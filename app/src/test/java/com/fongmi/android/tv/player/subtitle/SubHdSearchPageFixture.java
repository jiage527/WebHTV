package com.fongmi.android.tv.player.subtitle;

/**
 * Real markup captured from SubHD's search page for one query, kept verbatim so
 * the parser is tested against what the site actually serves. A provider-side
 * markup change then shows up as a failing assertion rather than as an empty
 * result list on a device.
 *
 * <p>Two result cards are kept; the surrounding page chrome is not relevant to
 * the parser and would only make the fixture harder to read.
 */
final class SubHdSearchPageFixture {

    static final String INCEPTION = """
            <div class="bg-white shadow-sm rounded-3 mb-4">
              <div class="row">
                <div class="col-2 d-none d-lg-block">

                    <a href='/d/3541415'>
                      <div class="pics">
                        <img class="rounded-start" src="https://img.subhd.me/poster/douban/3541415_600.webp" alt="盗梦空间 Inception">
                      </div>
                    </a>

                </div>
                <div class="col-lg-10">
                  <div class="pt-3 pe-3 pb-2 ps-3 ps-lg-0 position-relative">
                    <div class="clearfix">
                      <div class="float-start f16 fw-bold">

                          <a class="link-dark align-middle" href='/a/58k0EQ' target="_blank" rel="noopener noreferrer">盗梦空间</a>

                      </div>

                      <div class="view-text text-secondary">
                        <a href='/a/58k0EQ' class='link-dark' target="_blank" rel="noopener noreferrer">
                          Inception.2010.2160p.BluRay.x265.10bit.SDR.DTS-HD.MA.5.1-SWTYBLZ
                        </a>
                      </div>
                      <div class="text-truncate py-2 f11">
                        <span class="rounded p-1 me-1 text-white" style="background-color:#00a3ff!important">官方字幕</span>
                        <span class="p-1 fw-bold">双语</span>
                        <span class="p-1 fw-bold">简体</span><span class="p-1 fw-bold">英语</span>
                        <span class="p-1 text-secondary">ASS</span>


                      </div>
                      <div class="pt-2 text-secondary f12">
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="currentColor" class="bi bi-file-earmark-fill" viewBox="0 0 16 16">
                          <path d="M14 4.5V14a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V2a2 2 0 0 1 2-2h5.5L14 4.5zm-3 0A1.5 1.5 0 0 1 9.5 3V1H4a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V4.5h-2z"/>
                        </svg>
                        <span class='align-text-top me-3'>205k</span>
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="currentColor" class="bi bi-download" viewBox="0 0 16 16">
                          <path fill-rule="evenodd" d="M1 8a7 7 0 1 0 14 0A7 7 0 0 0 1 8zm15 0A8 8 0 1 1 0 8a8 8 0 0 1 16 0zM8.5 4.5a.5.5 0 0 0-1 0v5.793L5.354 8.146a.5.5 0 1 0-.708.708l3 3a.5.5 0 0 0 .708 0l3-3a.5.5 0 0 0-.708-.708L8.5 10.293V4.5z"/>
                        </svg>
                        <span class="align-text-top me-3">1354</span>
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="currentColor" class="bi bi-clock-fill" viewBox="0 0 16 16">
                          <path d="M8 3.5a.5.5 0 0 0-1 0V9a.5.5 0 0 0 .252.434l3.5 2a.5.5 0 0 0 .496-.868L8 8.71V3.5z"/>
                          <path d="M8 16A8 8 0 1 0 8 0a8 8 0 0 0 0 16zm7-8A7 7 0 1 1 1 8a7 7 0 0 1 14 0z"/>
                        </svg>
                        <span class="align-text-top me-3"><time datetime="2026-07-26T18:11:56.000Z" data-local-time="relative">07-26 18:11</time></span>
                      </div>
                      <div class="pt-1 f12 text-secondary">
                        发布人 <a class="fw-bold text-dark" href='/u/装B王' target='_blank'>装B王</a>

                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>



            <div class="bg-white shadow-sm rounded-3 mb-4">
              <div class="row">
                <div class="col-2 d-none d-lg-block">

                    <a href='/d/3541415'>
                      <div class="pics">
                        <img class="rounded-start" src="https://img.subhd.me/poster/douban/3541415_600.webp" alt="盗梦空间 Inception">
                      </div>
                    </a>

                </div>
                <div class="col-lg-10">
                  <div class="pt-3 pe-3 pb-2 ps-3 ps-lg-0 position-relative">
                    <div class="clearfix">
                      <div class="float-start f16 fw-bold">

                          <a class="link-dark align-middle" href='/a/kAlURK' target="_blank" rel="noopener noreferrer">盗梦空间</a>

                      </div>

                      <div class="view-text text-secondary">
                        <a href='/a/kAlURK' class='link-dark' target="_blank" rel="noopener noreferrer">
                          INCEPTION《盗梦空间》
                        </a>
                      </div>
                      <div class="text-truncate py-2 f11">
                        <span class="rounded p-1 me-1 text-white" style="background-color:#6c757d!important">其他来源</span>

                        <span class="p-1 fw-bold">繁体</span>
                        <span class="p-1 text-secondary">ASS</span><span class="p-1 text-secondary">SUP</span>


                      </div>
                      <div class="pt-2 text-secondary f12">
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="currentColor" class="bi bi-file-earmark-fill" viewBox="0 0 16 16">
                          <path d="M14 4.5V14a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V2a2 2 0 0 1 2-2h5.5L14 4.5zm-3 0A1.5 1.5 0 0 1 9.5 3V1H4a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V4.5h-2z"/>
                        </svg>
                        <span class='align-text-top me-3'>4503k</span>
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="currentColor" class="bi bi-download" viewBox="0 0 16 16">
                          <path fill-rule="evenodd" d="M1 8a7 7 0 1 0 14 0A7 7 0 0 0 1 8zm15 0A8 8 0 1 1 0 8a8 8 0 0 1 16 0zM8.5 4.5a.5.5 0 0 0-1 0v5.793L5.354 8.146a.5.5 0 1 0-.708.708l3 3a.5.5 0 0 0 .708 0l3-3a.5.5 0 0 0-.708-.708L8.5 10.293V4.5z"/>
                        </svg>
                        <span class="align-text-top me-3">482</span>
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="currentColor" class="bi bi-clock-fill" viewBox="0 0 16 16">
                          <path d="M8 3.5a.5.5 0 0 0-1 0V9a.5.5 0 0 0 .252.434l3.5 2a.5.5 0 0 0 .496-.868L8 8.71V3.5z"/>
                          <path d="M8 16A8 8 0 1 0 8 0a8 8 0 0 0 0 16zm7-8A7 7 0 1 1 1 8a7 7 0 0 1 14 0z"/>
                        </svg>
                        <span class="align-text-top me-3"><time datetime="2025-10-07T04:10:02.000Z" data-local-time="relative">2025-10-07</time></span>
                      </div>
                      <div class="pt-1 f12 text-secondary">
                        发布人 <a class="fw-bold text-dark" href='/u/SubKing' target='_blank'>SubKing</a>

                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>



            """;

    private SubHdSearchPageFixture() {
    }
}
