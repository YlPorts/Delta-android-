// Exercises the actual production host and real source-built engines.
#define DELTA_HOST_TEST
#include "../../app/src/main/cpp/delta_android.cpp"
#include <cstdlib>
#include <iostream>
#include <stdexcept>

void require(bool ok, const std::string &message) { if (!ok) throw std::runtime_error(message); }
void run(unsigned n) {
    for (unsigned i = 0; i < n; ++i) { samples.clear(); retro_run(); require(callbackError.empty(), callbackError); }
}
std::vector<uint8_t> readFile(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in),std::istreambuf_iterator<char>()};
}
int main(int argc, char **argv) {
    try {
        require(argc == 4, "Usage: core_test core.so diagnostic.gb|nes output-dir");
        const std::string lib=argv[1], rom=argv[2], directory=argv[3];
        auto error = openCore(lib,rom,directory);
        require(error.empty(),error);
        run(180);
        require(frameWidth >= 160 && frameHeight >= 144,"No framebuffer");
        require(samples.size() > 0,"No PCM audio");
        require(std::any_of(samples.begin(),samples.end(),[](int16_t s) { return s != 0; }),"PCM is silent");
        auto *ram=static_cast<uint8_t *>(retro_get_memory_data(0));
        require(ram && retro_get_memory_size(0) > 4,"No battery RAM");
        require(ram[0] == 0x42 && ram[1] != 0,"Diagnostic CPU program did not execute");
        const auto frame=pixels;
        run(3); require(pixels != frame,"Video did not change");
        buttonBits = 1u<<8; run(4);
        require(ram[rom.find(".nes") != std::string::npos ? 2 : 3] == 1,"A input did not reach emulated CPU");
        require(saveState(0).empty(),"Cannot save state");
        const uint8_t counter=ram[1];
        run(10); require(ram[1] != counter,"CPU stalled");
        require(loadState(0).empty(),"Cannot load same-core state");
        require(ram[1] == counter,"State did not restore actual RAM");
        auto state=readFile(statePath(0));
        require(state.size() > 40 && !std::memcmp(state.data(),"DLTAST02",8),"Missing state envelope");
        auto bad=state; bad.back()^=0xff;
        require(atomicWrite(statePath(0),bad.data(),bad.size()).empty(),"Test fixture write failed");
        require(!loadState(0).empty() && ram[1] == counter,"Corrupt state changed running game");
        bad=state; bad[16]^=0x40;
        require(atomicWrite(statePath(0),bad.data(),bad.size()).empty(),"Test fixture write failed");
        require(!loadState(0).empty(),"Wrong ROM identity accepted");
        // Existing raw 0.2 save states are preserved and readable.
        require(atomicWrite(statePath(1),state.data()+40,state.size()-40).empty(),"Legacy fixture");
        require(loadState(1).empty(),"Legacy state migration broken");
        require(saveRam().empty(),"SRAM flush failed");
        const auto saved=readFile(rom+".srm");
        const size_t rtcSize=retro_get_memory_size(1);
        if (rtcSize) require(readFile(rom+".rtc").size()==rtcSize,"RTC persistence missing");
        closeCore();
        require(openCore(lib,rom,directory).empty(),"Reopen failed");
        require(!std::memcmp(retro_get_memory_data(0),saved.data(),saved.size()),"SRAM persistence broken");
        closeCore();
        const uint8_t truncated=0x7f;
        require(atomicWrite(rom+".srm",&truncated,1).empty(),"Truncated save fixture");
        require(!openCore(lib,rom,directory).empty(),"Truncated SRAM accepted");
        closeCore();
        require(readFile(rom+".srm")==std::vector<uint8_t>{truncated},"Rejected SRAM was overwritten");
        std::remove((rom+".srm").c_str());
        require(!openCore(directory+"/missing.so",rom,directory).empty(),"Missing core accepted");
        closeCore(); closeCore();
        std::cout << "PASS: real CPU/video/PCM/input/SRAM/states/corruption/legacy/cleanup: " << lib << "\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "FAIL: " << e.what() << "\n"; closeCore(); return 1;
    }
}
