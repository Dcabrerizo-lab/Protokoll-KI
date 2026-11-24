from pkpc import hardware
from pkpc.hardware import Gpu, Hardware
from pkpc.llm import attempts


def test_parse_list_devices():
    out = """ggml_vulkan: Found 2 Vulkan devices:
Available devices:
  Vulkan0: NVIDIA GeForce RTX 3060 (12288 MiB, 11800 MiB free)
  Vulkan1: Intel(R) UHD Graphics 770 (16000 MiB, 15000 MiB free)
"""
    g = hardware.parse_list_devices(out)
    assert [x.name for x in g] == ["NVIDIA GeForce RTX 3060", "Intel(R) UHD Graphics 770"]
    assert g[0].dedicated and not g[1].dedicated
    assert g[0].vram_mb == 12288 and g[0].free_mb == 11800
    hw = Hardware(32, 8, 16, g)
    assert hw.gpu.name.startswith("NVIDIA") and round(hw.vram_gb) == 12


def test_choose_fastest_good_set():
    rtx = [Gpu("NVIDIA GeForce RTX 4070", 12282)]
    assert hardware.choose_set_id(Hardware(32, 8, 16, rtx)) == "ultra"          # Grafikkarte: großes Modell
    assert hardware.choose_set_id(Hardware(64, 16, 32, [])) == "premium"        # nur Prozessor: schnell + gut
    assert hardware.choose_set_id(Hardware(16, 4, 8, [Gpu("Intel(R) Iris(R) Xe Graphics", 8000, dedicated=False)])) == "premium"
    assert hardware.choose_set_id(Hardware(16, 4, 8, [Gpu("NVIDIA GeForce GTX 1650", 4096)])) == "premium"
    assert hardware.choose_set_id(Hardware(4, 2, 4, [])) == "standard"


def test_gpu_fit_and_attempts():
    hw = Hardware(32, 8, 16, [Gpu("AMD Radeon RX 7600", 8176)])
    assert hardware.gpu_fits(hw, 5_027_783_488, 16384)
    assert not hardware.gpu_fits(Hardware(32, 8, 16, [Gpu("GTX 1050", 2048)]), 2_497_280_000, 16384)
    assert attempts(True)[0][:2] == ["-ngl", "999"] and "-fa" in attempts(True)[0]
    assert all(a[1] == "0" for a in attempts(False))
    assert hardware.llm_threads(Hardware(16, 6, 12)) == (6, 12)
