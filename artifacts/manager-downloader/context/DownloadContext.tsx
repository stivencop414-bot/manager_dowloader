import AsyncStorage from '@react-native-async-storage/async-storage';
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';

export type DownloadStatus = 'active' | 'paused' | 'completed';

export type DownloadItem = {
  id: string;
  filename: string;
  url: string;
  status: DownloadStatus;
  progress: number;
  speed: string;
  downloaded: string;
  total: string;
  remaining: string;
  category: 'Video' | 'Documento' | 'Archivo';
};

type DownloadContextValue = {
  downloads: DownloadItem[];
  activeCount: number;
  completedCount: number;
  pausedCount: number;
  isReady: boolean;
  togglePause: (id: string) => void;
  addDownload: (url: string) => void;
};

const STORAGE_KEY = '@manager-downloader/downloads';

const starterDownloads: DownloadItem[] = [
  {
    id: 'demo-1',
    filename: 'Curso de fotografía — Lección 04.mp4',
    url: 'https://media.example.com/curso-fotografia/leccion-04.mp4',
    status: 'active',
    progress: 0.68,
    speed: '2.4 MB/s',
    downloaded: '486 MB',
    total: '712 MB',
    remaining: '1 min 34 s',
    category: 'Video',
  },
  {
    id: 'demo-2',
    filename: 'Manual de usuario.pdf',
    url: 'https://docs.example.com/manual-usuario.pdf',
    status: 'completed',
    progress: 1,
    speed: '—',
    downloaded: '18.2 MB',
    total: '18.2 MB',
    remaining: 'Completado',
    category: 'Documento',
  },
  {
    id: 'demo-3',
    filename: 'Recursos para el proyecto.zip',
    url: 'https://files.example.com/recursos-proyecto.zip',
    status: 'paused',
    progress: 0.34,
    speed: '—',
    downloaded: '84 MB',
    total: '248 MB',
    remaining: 'En pausa',
    category: 'Archivo',
  },
];

const DownloadContext = createContext<DownloadContextValue | undefined>(
  undefined,
);

function filenameFromUrl(value: string) {
  const withoutQuery = value.split('?')[0];
  const candidate = withoutQuery.split('/').filter(Boolean).pop();
  return candidate ? decodeURIComponent(candidate) : 'Nueva descarga';
}

export function DownloadProvider({ children }: { children: React.ReactNode }) {
  const [downloads, setDownloads] = useState<DownloadItem[]>(starterDownloads);
  const [isReady, setIsReady] = useState(false);

  useEffect(() => {
    let isMounted = true;

    AsyncStorage.getItem(STORAGE_KEY)
      .then((stored) => {
        if (!isMounted) return;
        if (stored) {
          const parsed = JSON.parse(stored) as DownloadItem[];
          if (Array.isArray(parsed) && parsed.length > 0) {
            setDownloads(parsed);
          }
        }
      })
      .catch(() => {
        // The in-memory starter list keeps the first launch usable if storage
        // is unavailable on a preview device.
      })
      .finally(() => {
        if (isMounted) setIsReady(true);
      });

    return () => {
      isMounted = false;
    };
  }, []);

  useEffect(() => {
    if (!isReady) return;
    AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(downloads)).catch(() => {
      // Persistence is best-effort on web previews; native remains local-first.
    });
  }, [downloads, isReady]);

  const togglePause = useCallback((id: string) => {
    setDownloads((current) =>
      current.map((download) => {
        if (download.id !== id || download.status === 'completed') {
          return download;
        }
        return {
          ...download,
          status: download.status === 'paused' ? 'active' : 'paused',
          speed: download.status === 'paused' ? '2.4 MB/s' : '—',
          remaining:
            download.status === 'paused' ? download.remaining : 'En pausa',
        };
      }),
    );
  }, []);

  const addDownload = useCallback((url: string) => {
    const filename = filenameFromUrl(url);
    const newDownload: DownloadItem = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      filename,
      url,
      status: 'active',
      progress: 0,
      speed: 'Preparando…',
      downloaded: '0 B',
      total: '—',
      remaining: 'Calculando…',
      category: 'Archivo',
    };
    setDownloads((current) => [newDownload, ...current]);
  }, []);

  const value = useMemo(
    () => ({
      downloads,
      activeCount: downloads.filter((item) => item.status === 'active').length,
      completedCount: downloads.filter((item) => item.status === 'completed')
        .length,
      pausedCount: downloads.filter((item) => item.status === 'paused').length,
      isReady,
      togglePause,
      addDownload,
    }),
    [addDownload, downloads, isReady, togglePause],
  );

  return (
    <DownloadContext.Provider value={value}>
      {children}
    </DownloadContext.Provider>
  );
}

export function useDownloads() {
  const context = useContext(DownloadContext);
  if (!context) {
    throw new Error('useDownloads must be used within a DownloadProvider');
  }
  return context;
}